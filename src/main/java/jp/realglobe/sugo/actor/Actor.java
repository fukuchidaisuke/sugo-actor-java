package jp.realglobe.sugo.actor;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import io.socket.client.Ack;
import io.socket.client.Manager;
import io.socket.client.Socket;
import jp.realglobe.sg.socket.Constants;

/**
 * Actor
 */
public class Actor {

    private static final Logger LOG = Logger.getLogger(Actor.class.getName());

    private static final String NAMESPACE = "/actors";

    // sugos 送信データのキー
    private static final String KEY_KEY = "key";
    private static final String KEY_NAME = "name";
    private static final String KEY_SPEC = "spec";
    private static final String KEY_MODULE = "module";
    private static final String KEY_EVENT = "event";
    private static final String KEY_DATA = "data";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_METHOD = "method";
    private static final String KEY_STATUS = "status";
    private static final String KEY_PAYLOAD = "payload";

    private final String hub;
    private final String key;
    private Runnable onConnect;

    private final Map<String, Module> modules;

    private final CountDownLatch connected;

    private Socket socket;

    private boolean greeted;

    /**
     * 作成する
     * @param hub サーバー URL
     * @param key キー
     * @param name 名前
     * @param description 説明
     */
    public Actor(final String hub, final String key, final String name, final String description) {
        this.hub = hub;
        this.key = key;
        this.modules = new HashMap<>();
        this.connected = new CountDownLatch(1);
    }

    /**
     * つながるまで待つ
     * @throws InterruptedException 割り込まれた
     */
    public void waitConnect() throws InterruptedException {
        connect();
        this.connected.await();
    }

    /**
     * サーバーにつなぐ
     */
    public synchronized void connect() {
        if (this.socket != null) {
            LOG.info("Already connected");
            return;
        }
        this.socket = (new Manager(URI.create(this.hub))).socket(NAMESPACE);

        this.socket.on(Socket.EVENT_CONNECT, args -> {
            LOG.fine("Connected to " + this.hub);
            greet();
        });
        this.socket.on(Socket.EVENT_DISCONNECT, args -> LOG.fine("Disconnected from " + this.hub));
        this.socket.on(Constants.RemoteEvents.PERFORM, this::perform);
        this.socket.connect();
    }

    /**
     * サーバーに最初の挨拶
     */
    private synchronized void greet() {
        if (this.socket == null) {
            // 終了
            return;
        }

        final Map<String, Object> data = new HashMap<>();
        data.put(KEY_KEY, this.key);
        this.socket.emit(Constants.GreetingEvents.HI, new JSONObject(data), (Ack) args -> {
            LOG.fine(this.socket.id() + " greeted");
            processAfterGreeting();
        });
    }

    private synchronized void processAfterGreeting() {
        this.greeted = true;

        final List<Map.Entry<String, Module>> entries = new ArrayList<>(this.modules.entrySet());
        sendSpecification(entries);
    }

    /**
     * サーバーに仕様を通知
     * @param entries 通知するモジュール
     */
    private synchronized void sendSpecification(final List<Map.Entry<String, Module>> entries) {
        if (this.socket == null) {
            // 終了
            return;
        }

        if (entries.isEmpty()) {
            this.connected.countDown();
            if (this.onConnect != null) {
                this.onConnect.run();
            }
            return;
        }

        final Map.Entry<String, Module> entry = entries.remove(entries.size() - 1);

        final String moduleName = entry.getKey();
        final Object specification = Specification.generateSpecification(entry.getValue());

        final Map<String, Object> data = new HashMap<>();
        data.put(KEY_NAME, moduleName);
        data.put(KEY_SPEC, specification);

        this.socket.emit(Constants.RemoteEvents.SPEC, new JSONObject(data), (Ack) args -> {
            LOG.fine(this.socket.id() + " sent specification " + moduleName);
            sendSpecification(entries);
        });
    }

    /**
     * モジュール関数を実行
     * @param args io.socket.client.Ack.call を参照
     */
    private void perform(final Object[] args) {
        try {
            final JSONObject data = (JSONObject) args[0];
            if (!this.key.equals(data.getString(KEY_KEY))) {
                return;
            }
            final Module module;
            synchronized (this) {
                final String moduleName = data.getString(KEY_MODULE);
                if (!this.modules.containsKey(moduleName)) {
                    return;
                }
                module = this.modules.get(moduleName);
            }
            final String methodName = data.getString(KEY_METHOD);
            final Class<?> returnType = module.getReturnType(methodName);
            if (returnType == null) {
                throw new RuntimeException("function " + methodName + " does not exist");
            }
            final Object[] parameters = JsonUtils.convertToObject(data.getJSONArray(KEY_PARAMS));
            final Object returnValue = module.invoke(methodName, parameters);

            final Ack ack = (Ack) args[args.length - 1];
            final Map<String, Object> responseData = new HashMap<>();
            responseData.put(KEY_STATUS, Constants.AcknowledgeStatus.OK);
            if (returnType != Void.TYPE) {
                responseData.put(KEY_PAYLOAD, returnValue);
            }
            ack.call(new JSONObject(responseData));
        } catch (final JSONException e) {
            throw new RuntimeException(e);
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (final IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (final InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * つながったときに実行する関数を登録する
     * @param onConnect つながったときに実行する関数
     */
    public synchronized void setOnConnect(final Runnable onConnect) {
        this.onConnect = onConnect;
    }

    /**
     * モジュールを登録する
     * @param moduleName モジュール名
     * @param moduleVersion モジュールバージョン
     * @param moduleDescription モジュールの説明
     * @param module モジュール
     * @return モジュール用のイベント送信機
     */
    public synchronized Emitter addModule(final String moduleName, final String moduleVersion, final String moduleDescription, final Object module) {
        this.modules.put(moduleName, new Module(moduleVersion, moduleDescription, module));
        final Emitter emitter;
        if (module instanceof Emitter) {
            emitter = (Emitter) module;
        } else {
            emitter = new EmitterImpl(moduleName);
        }
        emitter.setEmitter(this::emit);
        return emitter;
    }

    static interface ActorEmitter {
        void emit(String moduleName, String event, Object data);
    }

    private class EmitterImpl extends Emitter {
        EmitterImpl(final String name) {
            super(name);
        }
    }

    /**
     * イベント送信
     * @param event イベント
     * @param data JSON 化可能なデータ
     */
    synchronized void emit(final String moduleName, final String event, final Object data) {
        final Map<String, Object> wrapData = new HashMap<>();
        wrapData.put(KEY_KEY, this.key);
        wrapData.put(KEY_MODULE, moduleName);
        wrapData.put(KEY_EVENT, event);
        if (data != null) {
            wrapData.put(KEY_DATA, data);
        }
        this.socket.emit(Constants.RemoteEvents.PIPE, new JSONObject(wrapData));
    }

    /**
     * 切断する
     */
    public synchronized void disconnect() {
        if (this.socket == null) {
            LOG.info("Not connecting");
            return;
        }
        final Socket socket0 = this.socket;
        this.socket = null;

        if (!this.greeted) {
            socket0.disconnect();
            return;
        }
        this.greeted = false;

        final Map<String, Object> data = new HashMap<>();
        data.put(KEY_KEY, this.key);
        socket0.emit(Constants.GreetingEvents.BYE, new JSONObject(data), (Ack) args -> socket0.disconnect());
    }

}
