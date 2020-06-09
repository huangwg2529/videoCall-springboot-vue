import kurentoUtils from 'kurento-utils'  // npm install kurento-utils

// 创建与后端的视频通话用websocket通信，视频通话必须用https
let wss = new WebSocket('wss://localhost:8443/videoCall'); // 记得改成服务器ip

export var videoInput;
export var videoOutput;
var webRtcPeer;
// 下面都是用的这个静态数据，需绑定呼叫用户的数据
export var callerIDtmp = 1000025;
export var calleeIDtmp = 1000026;

export function setInputAndOutput(input, output) {
	videoInput = input;
	videoOutput = output;
}

// 发送消息
function sendMessage(message) {
	var jsonMessage = JSON.stringify(message);
	console.log('Sending message: ' + jsonMessage);
    wss.send(jsonMessage);
}

// 收到消息
wss.onmessage = function(message) { // eslint-disable-line no-unused-vars
	var parsedMessage = JSON.parse(message.data);
	console.info('Received message: ' + message.data);

	switch (parsedMessage.type) {
    // 登录时把用户记录在在线用户列表中，如果该过程出问题了就有这个消息。一般不可能出现，仅限debug用
	case 'loginResponse': 
		// registerResponse(parsedMessage);
		break;
	case 'callResponse': // 用户主动发起通话时会向服务器发送消息，服务器处理结果会返回该消息(不在线、拒绝、同意等)
		callResponse(parsedMessage);
		break;
	case 'incomingCall': // 用户收到了其他人的通话申请，就会收到服务器发来的这个消息
		incomingCall(parsedMessage);
		break;
	case 'startCommunication': // 被呼叫者同意申请后，服务器处理时会发来这个消息
		startCommunication(parsedMessage);
		break;
	case 'stopCommunication': // 对方主动停止通话，你会收到这个消息
		console.info('Communication ended by remote peer');
		stop(true);
		break;
	case 'iceCandidate':
		webRtcPeer.addIceCandidate(parsedMessage.candidate, function(error) {
			if (error)
				return console.error('Error adding candidate: ' + error);
		});
		break;
	default:
		console.error('Unrecognized message', parsedMessage);
	}
}

function callResponse(message) {
	if (message.callResponse == 'notOnline') { // 对方不在线
		console.info('Your friend is not online. Closing call');
		// stop();
	} else if(message.callResponse == 'isBusy' ) { // 对方正忙
		console.info('Your friend is busy. Closing call');
		stop();
	} else if(message.callResponse == 'rejected') {
		console.info('You are rejected.');
		stop();
	} else {
		webRtcPeer.processAnswer(message.sdpAnswer, function(error) {
			if (error)
				return console.error(error);
		});
	}
}

// 收到呼叫信息
function incomingCall(message) {
	//if (confirm('User ' + message.from + ' is calling you. Do you accept the call?')) {
		// showSpinner(videoInput, videoOutput);
	// 用户接受或者拒接。
	var isAccepted = true;
	if(isAccepted) { // 这是接受情况
		callerIDtmp = message.callerID;
		console.log("incomingCall accepted");

		// 添加自己的中继服务器的配置，否则会默认指向谷歌的服务器
		var iceservers = {
			"iceServers": [
			{
				urls:"stun:your_server_ip:3478"
			},
			{
				urls: ["turn:your_server_ip:3478"],
				username: "kurento",
				credential: "kurento"
			}
			]
		};

		var options = {
			localVideo: videoInput,
			remoteVideo: videoOutput,
			onicecandidate: onIceCandidate,
			onerror: onError,
			configuration: iceservers
		};
		
		webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
				function(error) {
					if (error) {
						return console.error(error);
					}
					webRtcPeer.generateOffer(onOfferIncomingCall);
				});

	} else {
		var response = {
			type : 'incomingCallResponse',
			callerID : message.callerID,
			callResponse : 'rejected'
		};
		sendMessage(response);
		stop();
	}
}

// 向后端发送接受信息
function onOfferIncomingCall(error, offerSdp) {
	if (error)
		return console.error("Error generating the offer");
	var response = {
		type : 'incomingCallResponse',
		callerID : callerIDtmp,
		callResponse : 'accepted',
		sdpOffer : offerSdp
	};
	sendMessage(response);
}


function startCommunication(message) {
	webRtcPeer.processAnswer(message.sdpAnswer, function(error) {
		if (error)
			return console.error(error);
	});
}

export function stop(message) {
	if (webRtcPeer) {
		webRtcPeer.dispose();
		webRtcPeer = null;

		if (!message) {
			var stopMessage = {
				type : 'stop'
			}
			sendMessage(stopMessage);
		}
	}
	hideSpinner(videoInput, videoOutput);
}

export function login(loginID) {
	var loginMessage = {
		type: 'login',
		userID: loginID
	};
	sendMessage(loginMessage);
}

// 发起呼叫
export function call(callerID, calleeID) {
	callerIDtmp = callerID;
	calleeIDtmp = calleeID;

	//showSpinner(videoInput, videoOutput);

	// 添加自己的中继服务器的配置，否则会默认指向谷歌的服务器
	var iceservers = {
		"iceServers": [
		{
			urls:"stun:your_server_ip:3478"
		},
		{
			urls: ["turn:your_server_ip:3478"],
			username: "kurento",
			credential: "kurento"
		}
		]
	};

	var options = {
		localVideo : videoInput,
		remoteVideo : videoOutput,
		onicecandidate : onIceCandidate, // 向服务器发送 onIceCandidate 消息？
		onerror: onError,
		configuration: iceservers
	}
	// 调js库，启用WebRtc通信
	webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
			function(error) {
				if (error) {
					return console.error(error);
				}
				webRtcPeer.generateOffer(onOfferCall);
			});
}

// 向后端发送呼叫信息
function onOfferCall(error, offerSdp) {
	if (error)
		return console.error('Error generating the offer');
	console.log('Invoking SDP offer callback function');
	var message = {
		type : 'call',
		callerID : callerIDtmp,
		calleeID : calleeIDtmp,
		sdpOffer : offerSdp
	};
	sendMessage(message);
}

// 向服务器发送 onIceCandidate 消息
function onIceCandidate(candidate) {
	// console.log("Local candidate" + JSON.stringify(candidate));

	var message = {
		type : 'onIceCandidate',
		candidate : candidate
	};
	sendMessage(message);
}

function onError() {
}

// 好像是呼叫请求时转圈圈用的
function showSpinner() { // eslint-disable-line no-unused-vars
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].poster = '../logo.png';
		arguments[i].style.background = 'center transparent url("../logo.png") no-repeat';
	}
}

function hideSpinner() {
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].src = '';
		arguments[i].poster = '../logo.png';
		arguments[i].style.background = '';
	}
}