package com.hwg.videocall.video;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value = "/videoCall")
public class VideoHandler {

    // ConcurrentHashMap: 相当于线程安全的HashMap.
    // 这里存储 useruserID-UserSession
    private static ConcurrentHashMap<String, UserSession> usersByUserID = new ConcurrentHashMap<>();
    // 这里则是 Session.id-UserSession
    private static ConcurrentHashMap<String, UserSession> usersBySessionId = new ConcurrentHashMap<>();


    private static final Logger logger = LoggerFactory.getLogger(VideoHandler.class);
    private static final Gson gson = new GsonBuilder().create(); // Gson: 把 Java 对象转换为 Json 表达式

    private final ConcurrentHashMap<String, CallMediaPipeline> pipelines = new ConcurrentHashMap<>();

    // @Autowired
    private static KurentoClient kurento = KurentoClient.create();

    /*
     * 连接成功建立时调用
     * 不用执行
     */
    @OnOpen
    public void onOpen(Session session) {
    }

    /*
     * 关闭连接时调用
     * 需要关闭前面建立的媒体管道以及移出在线用户列表
     */
    @OnClose
    public void onClose(Session session) throws Exception {
        stop(session);
        removeBySession(session);
    }

    /*
     * 连接出错时调用
     * 修改用户状态
     */
    @OnError
    public void onError(Session session, Throwable error) {
        UserSession user = getUserSessionBySession(session);
        if (user != null)
            user.setStateFree();
        error.printStackTrace();
    }

    /*
     * 收到消息时调用
     * 根据消息的类别调用不同的方法
     */
    @OnMessage
    public void onMessage(Session session, String message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
        UserSession user = getUserSessionBySession(session);
        switch (jsonMessage.get("type").getAsString()) {
            case "login":
                try {
                    login(session, jsonMessage);
                } catch (Throwable t) {
                    ErrorResponse(t, session, "loginResponse");
                }
                break;
            case "call":
                try {
                    call(user, jsonMessage);
                } catch (Throwable t) {
                    ErrorResponse(t, session, "callResponse");
                }
                break;
            case "incomingCallResponse":
                incomingCallResponse(user, jsonMessage);
                break;
            case "onIceCandidate": {
                JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();
                if (user != null) {
                    IceCandidate cand =
                            new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                                    .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidate(cand);
                }
                break;
            }
            case "stop":
                stop(session);
                break;
            default:
                break;
        }

    }

    // 用户上线，加入在线列表
    private void login(Session session, JsonObject jsonObject) throws IOException {
        String userID = jsonObject.get("userID").getAsString();
        UserSession user = new UserSession(session, userID);
        online(user);
        logger.info("userID: " + userID + "is online.");
    }

    // 检查用户是否在线，没有就返回对方不在线消息，有就向被呼叫用户发送消息
    private void call(UserSession caller, JsonObject jsonMessage) throws IOException {
        String callerID = jsonMessage.get("callerID").getAsString();
        String calleeID = jsonMessage.get("calleeID").getAsString();

        JsonObject response = new JsonObject();
        if (!exists(calleeID)) {
            response.addProperty("type", "callResponse");
            response.addProperty("callResponse", "notOnline");
            caller.sendMessage(response);
            logger.info("videoCall: " + callerID + " call to " + calleeID + ": NotOnline.");
            return;
        }

        UserSession callee = getUserSessionByUserID(calleeID);

        // 判断对方是不是正忙
        if (callee.getState() == 1) {
            response.addProperty("type", "callResponse");
            response.addProperty("callResponse", "isBusy");
            caller.sendMessage(response);
            logger.info("videoCall: " + callerID + " call to " + calleeID + ": isBusy.");
            return;
        }

        // 设置呼叫者正忙状态
        caller.setStateCalling();

        caller.setSdpOffer(jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
        caller.setCallingTo(calleeID);

        response.addProperty("type", "incomingCall");
        response.addProperty("callerID", callerID);

        callee.sendMessage(response);
        callee.setCallingFrom(callerID);
        logger.info("videoCall: " + callerID + " call to " + calleeID + ": send incomingCall to " + calleeID);
    }

    // 被呼叫者向服务器返回应答信息
    private void incomingCallResponse(UserSession callee, JsonObject jsonMessage) throws IOException {
        String callResponse = jsonMessage.get("callResponse").getAsString();
        String callerID = jsonMessage.get("callerID").getAsString();
        UserSession caller = getUserSessionByUserID(callerID);
        String calleeID = caller.getCallingTo();

        // 被呼叫者拒绝通话
        if (!"accepted".equals(callResponse)) {
            JsonObject response = new JsonObject();
            response.addProperty("type", "callResponse");
            response.addProperty("callResponse", jsonMessage.get("callResponse").getAsString());
            caller.sendMessage(response);
            caller.setStateFree();
            logger.info("videoCall: " + callerID + " call to " + calleeID + "callee rejected!");
            return;
        }

        // 如果对方接受了呼叫请求，建立媒体管道 p2p 连接双方
        logger.info("videoCall: " + callerID + " call to " + calleeID + "accepted");
        // 创建一个 CallMediaPipeline对象，以封装媒体管道的创建和管理。然后，该对象用于与用户的浏览器协商媒体交换。
        CallMediaPipeline pipeline = null;
        try {
            pipeline = new CallMediaPipeline(kurento);
            pipelines.put(caller.getSessionId(), pipeline);
            pipelines.put(callee.getSessionId(), pipeline);
            callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());
            pipeline.getCalleeWebRtcEp().addIceCandidateFoundListener(
                    new EventListener<IceCandidateFoundEvent>() {
                        @Override
                        public void onEvent(IceCandidateFoundEvent iceCandidateFoundEvent) {
                            JsonObject response = new JsonObject();
                            response.addProperty("type", "iceCandidate");
                            response.add("candidate", JsonUtils.toJsonObject(iceCandidateFoundEvent.getCandidate()));
                            try {
                                synchronized (callee.getSession()) {
                                    callee.sendMessage(response);
                                }
                            } catch (IOException e) {
                                logger.debug(e.getMessage());
                            }
                        }
                    });
            caller.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());
            pipeline.getCallerWebRtcEp().addIceCandidateFoundListener(
                    new EventListener<IceCandidateFoundEvent>() {
                        @Override
                        public void onEvent(IceCandidateFoundEvent iceCandidateFoundEvent) {
                            JsonObject response = new JsonObject();
                            response.addProperty("type", "iceCandidate");
                            response.add("candidate", JsonUtils.toJsonObject(iceCandidateFoundEvent.getCandidate()));
                            try {
                                synchronized (caller.getSession()) {
                                    caller.sendMessage(response);
                                }
                            } catch (IOException e) {
                                logger.debug(e.getMessage());
                            }
                        }
                    });

            String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
            String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
            JsonObject startCommunication = new JsonObject();
            startCommunication.addProperty("type", "startCommunication");
            startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

            synchronized (callee) {
                callee.sendMessage(startCommunication);
            }
            // 设置被呼叫者正忙
            callee.setStateCalling();

            pipeline.getCalleeWebRtcEp().gatherCandidates();

            String callerSdpOffer = getUserSessionByUserID(callerID).getSdpOffer();
            String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
            JsonObject response = new JsonObject();
            response.addProperty("type", "callResponse");
            response.addProperty("callResponse", "accepted");
            response.addProperty("sdpAnswer", callerSdpAnswer);

            synchronized (caller) {
                caller.sendMessage(response);
            }

            pipeline.getCallerWebRtcEp().gatherCandidates();

        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            if (pipeline != null) {
                pipeline.release();
            }

            pipelines.remove(caller.getSessionId());
            pipelines.remove(callee.getSessionId());

            JsonObject response = new JsonObject();
            response.addProperty("type", "callResponse");
            response.addProperty("callResponse", "exception");
            caller.sendMessage(response);
        }
    }

    // 停止通话
    private void stop(Session session) throws IOException {
        String sessionId = session.getId();
        if (pipelines.containsKey(sessionId)) {
            pipelines.get(sessionId).release();
            CallMediaPipeline pipeline = pipelines.remove(sessionId);
            pipeline.release();

            UserSession stopper = getUserSessionBySession(session);
            if (stopper != null) {
                stopper.setStateFree();
                UserSession stoppee =
                        (stopper.getCallingFrom() != null) ? getUserSessionByUserID(stopper.
                                getCallingFrom()) : stopper.getCallingTo() != null ? getUserSessionByUserID(stopper
                                .getCallingTo()) : null;

                if (stoppee != null) {
                    stoppee.setStateFree();
                    JsonObject message = new JsonObject();
                    message.addProperty("type", "stopCommmunication");
                    stoppee.sendMessage(message);
                    stoppee.clear();
                }
                stopper.clear();
            }
        }
    }

    // 消息处理异常时调用，关闭会话并向对方返回错误消息
    private void ErrorResponse(Throwable throwable, Session session, String responseId) throws IOException {
        stop(session);
        logger.error(throwable.getMessage(), throwable);
        JsonObject response = new JsonObject();
        response.addProperty("type", responseId);
        response.addProperty("content", "rejected");
        response.addProperty("error", throwable.getMessage());
        session.getAsyncRemote().sendText(response.toString());
    }

    // 下面是对上面两个 HashMap 的操作方法

    //用户上线，添加到 Map 中
    public void online(UserSession user) throws NullPointerException {
        usersByUserID.put(user.getuserID(), user);
        usersBySessionId.put(user.getSession().getId(), user);
    }

    public UserSession getUserSessionByUserID(String userID) {
        return usersByUserID.get(userID);
    }

    public UserSession getUserSessionBySession(Session session) {
        return usersBySessionId.get(session.getId());
    }

    public boolean exists(String userID) {
        return usersByUserID.keySet().contains(userID);
    }

    public UserSession removeBySession(Session session) {
        final UserSession user = getUserSessionBySession(session);
        if (user != null) {
            usersByUserID.remove(user.getuserID());
            usersBySessionId.remove(session.getId());
        }
        return user;
    }

    public void printOnlineUserID() {
        Iterator iter = usersByUserID.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry) iter.next();
            Object key = entry.getKey();
            Object value = entry.getValue();
            System.out.println(key + ":" + value);
        }
    }

}
