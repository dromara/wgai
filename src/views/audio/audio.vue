<template>
  <div id="app">
    <a-layout class="layout">
    <!--  -->
      <a-layout-content >
        <div class="site-layout-content">
          <a-button type="primary" @click="startRecording" :disabled="recording">开始录音</a-button>
          <a-button type="danger" @click="stopRecording" :disabled="!recording">停止录音</a-button>
          <a-divider />
          <div v-if="audioBlob">
            <audio :src="audioUrl" controls style="
    float: left;
    margin: 6px;
    padding: 5px;
    height: 50px;
"></audio>
            <a-button type="primary" @click="uploadAudio" style="height: 50px; margin-left:2%;    float: left;">识别音频</a-button>
               <!--   <a-button type="primary" @click="AiAudio" style="height: 50px; margin-left:2%;    float: left;">识别音频</a-button><br/><br/> -->
            <textarea v-model="aitext"  placeholder="识别内容输出" style="width: 100%;height:500px;"></textarea>
          </div>
          <a-message />
        </div>
      </a-layout-content>
      <a-layout-footer style="text-align: center">
     
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script>
    import Vue from 'vue'
import axios from 'axios';
import { message } from 'ant-design-vue';
  import {
    httpAction,
    getAction,
    uploadAction 
  } from '@/api/manage'
  import store from '@/store/'

  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'
export default {
  data() {
    return {
      mediaRecorder: null,
      audioBlob: null,
      audioUrl: null,
      recording: false,
      aitext:""
    };
  },
  mounted() {
    //初始化websocket
    this.initWebSocket();

  
  
  },
  methods: {
    startRecording() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        message.error('浏览器不支持音频录制功能');
        return;
      }

      navigator.mediaDevices.getUserMedia({ audio: true })
        .then(stream => {
          this.mediaRecorder = new MediaRecorder(stream);
          
          
          this.mediaRecorder.ondataavailable = this.handleDataAvailable;
          this.mediaRecorder.start();
          this.recording = true;
          message.info('录音开始');
        })
        .catch(error => {
          console.error('无法访问麦克风', error);
          message.error('无法访问麦克风或用户拒绝权限');
        });
    },
    stopRecording() {
      if (this.mediaRecorder) {
        this.mediaRecorder.stop();
        this.recording = false;
        message.info('录音停止');
      }
    },
    handleDataAvailable(event) {
      this.audioBlob = new Blob([event.data], { type: 'audio/wav' });
      this.audioUrl = URL.createObjectURL(this.audioBlob);
    },
    uploadAudio() {
      if (!this.audioBlob) return;

      const formData = new FormData();
      formData.append('file', this.audioBlob, 'audio.wav');
       formData.append('biz', "temp");

      // axios.post('/api/upload-audio', formData, {
      //   headers: {
      //     'Content-Type': 'multipart/form-data'
      //   }
      // }).then(response => {
      //   message.success('音频上传成功');
      // }).catch(error => {
      //   message.error('音频上传失败');
      //   console.error('音频上传失败', error);
      // });
      let that=this;
      uploadAction("/sys/common/upload", formData).then((res) => {
        if (res.success) {
          message.success("识别成功");
        
          that.AiAudio(res.message);
        } else {
          message.warning(res.message);
        }
      })
      
    },
    AiAudio(url){
      let that=this;
        getAction("/tab/tabAiHistory/addAudio", {path:url}).then((res) => {
          if (res.success) {
             that.aitext=res.result;
             message.success("识别成功");
          
          } else {
           message.warning("识别失败");
          }
        })
    },
    initWebSocket: function() {
      // WebSocket与普通的请求所用协议有所不同，ws等同于http，wss等同于https
      var userId = store.getters.userInfo.id;
      var url = window._CONFIG['domianURL'].replace("https://", "wss://").replace("http://", "ws://") +
        "/WebSocketAudio/" + userId;
      console.log(url);
      //update-begin-author:taoyan date:2022-4-22 for:  v2.4.6 的 websocket 服务端，存在性能和安全问题。 #3278
      let token = Vue.ls.get(ACCESS_TOKEN)
      this.websock = new WebSocket(url, [token]);
      this.websock.onopen = this.websocketonopen;
      this.websock.onerror = this.websocketonerror;
      this.websock.onmessage = this.websocketonmessage;
      this.websock.onclose = this.websocketclose;
    
    },
    websocketonopen: function() {
      this.heartCheckFun();
      console.log("WebSocket连接成功11111");
    
    },
    websocketonerror: function(e) {
      console.log("WebSocket连接发生错误111111");
    },
    websocketonmessage: function(e) {
      var data = eval("(" + e.data + ")");
  
    },
    websocketclose: function(e) {
      console.log("connection closed (" + e.code + ")");
    },
    
    websocketSend(text) {
      // 数据发送
        try {
          this.websock.send(text);
        } catch (err) {
          console.log("send failed (" + err.code + ")");
        }
      },
      heartCheckFun() {
        console.log("发送心跳")
      
        //心跳检测,每20s心跳一次
        this.heartbeatInterval = setInterval(() => {
          // 发送心跳消息
          this.websocketSend("HeartBeat");
        }, 20000); // 20秒
      
      
      },
  }
};
</script>

<style>
#app {
  min-height: 100vh;
}

.site-layout-content {
  background: #fff;
  padding: 24px;
  margin: 0;
}
</style>
