package com.hwg.videocall.video;

import com.google.gson.JsonObject;
import org.kurento.client.IceCandidate;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.Session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class UserSession {

    private static final Logger logger = LoggerFactory.getLogger(UserSession.class);

    private final String userID;
    private final Session session;

    private String sdpOffer;
    private String callingTo;
    private String callingFrom;
    private WebRtcEndpoint webRtcEndpoint;
    // IceCandidate是一个模板类，里面主要包含着会话描述协议
    // 集成自己了IP地址信息本地IP地址、公网IP地址、Relay服务端分配的地址等
    private final List<IceCandidate> candidateList = new ArrayList<IceCandidate>();

    private int state = 0;

    public UserSession(Session session, String userID) {
        this.session = session;
        this.userID = userID;
    }

    public Session getSession() {
        return session;
    }

    public String getuserID() {
        return userID;
    }

    public String getSdpOffer() {
        return sdpOffer;
    }

    public void setSdpOffer(String sdpOffer) {
        this.sdpOffer = sdpOffer;
    }

    public String getCallingTo() {
        return callingTo;
    }

    public void setCallingTo(String callingTo) {
        this.callingTo = callingTo;
    }

    public String getCallingFrom() {
        return callingFrom;
    }

    public void setCallingFrom(String callingFrom) {
        this.callingFrom = callingFrom;
    }

    public void sendMessage(JsonObject message) throws IOException {
        logger.info("Sending message from user '{}': {}", userID, message);
        session.getAsyncRemote().sendText(message.toString());
    }

    public String getSessionId() {
        return session.getId();
    }

    public void setWebRtcEndpoint(WebRtcEndpoint webRtcEndpoint) {
        this.webRtcEndpoint = webRtcEndpoint;

        for (IceCandidate e : candidateList) {
            this.webRtcEndpoint.addIceCandidate(e);
        }
        this.candidateList.clear();
    }

    public void addCandidate(IceCandidate candidate) {
        if (this.webRtcEndpoint != null) {
            this.webRtcEndpoint.addIceCandidate(candidate);
        } else {
            candidateList.add(candidate);
        }
    }

    public int getState() {
        return state;
    }

    public void setStateFree() {
        this.state = 0;
    }

    public void setStateCalling() {
        this.state = 1;
    }

    public void clear() {
        this.webRtcEndpoint = null;
        this.candidateList.clear();
    }
}
