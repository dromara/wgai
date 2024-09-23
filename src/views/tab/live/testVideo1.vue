<template >
  <a-card  :body-style='{padding:0}' class="j-address-list-right-card-box" :bordered="false">
    <div class="ceshi" style="width: 100%;height:50px;"> <input type="text" v-model="url">
      <button @click="geturl()">播放1</button>
      <button @click="closeurl()">销毁1</button>
    </div>
    </div>

    <div class="buttons-box1" id="buttonsBox2">


    </div>

  </a-card>
</template>

<script>
  import {
    getAction
  } from '@/api/manage'
  import store from '@/store/'
  import Vue from 'vue'
  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'
  let jessibucaPlayer = {};
  export default {
    name: 'testVideo1',

    components: {},
    props: ['value'],
    data() {
      return {
        description: '用户信息',
        cardLoading: true,
        positionInfo: {},
        _uid: 0,
        url: 'ws://192.168.0.252:8888/rtp/34020000001320000009_34020000001310000001.live.flv',
        heartbeatInterval: null,
        number: 0,
        datalist: []
      }
    },
    watch: {
      value: {
        immediate: true,
        handler(url) {
          console.log(url)
          this.url = url;
          this.closeurl();
          // this.initPro();

        }
      },
    },
    created() {},
    mounted() {
      //初始化websocket
      this.initWebSocket();
      this.initPro();


    },
    destroyed: function() { // 离开页面生命周期函数
      this.websocketclose();
      this.closeurl();
    },
    methods: {

      initPro() {
        let options = {
          container: document.getElementById('buttonsBox2'),
          videoBuffer: Number(4), // 缓存时长
          videoBufferDelay: Number(1000), // 1000s
          decoder: "../../../../static/decoder-pro.js",
          isResize: false,
          text: "",
          loadingText: "加载中",
          debug: false,
          debugLevel: "debug",
          useMSE: true,
          useSIMD: true,
          useWCS: true,
          useMThreading: true,
          showBandwidth: true, // 显示网速
          showPerformance: false, // 显示性能
          operateBtns: {
            fullscreen: false,
            screenshot: false,
            play: false,
            audio: false,
            ptz: false,
            quality: false,
            performance: false,
          },
          timeout: 10,
          heartTimeoutReplayUseLastFrameShow: false,
          audioEngine: "worklet",
          qualityConfig: ['普清', '高清', '超清', '4K', '8K'],
          defaultStreamQuality: '普清',
          forceNoOffscreen: false,
          isNotMute: false,
          heartTimeout: 10,
          ptzClickType: 'mouseDownAndUp',
          ptzZoomShow: true,
          ptzMoreArrowShow: true,
          ptzApertureShow: true,
          ptzFocusShow: true,
          isDropSameTimestampGop: true,
           useCanvasRender: 'canvas',
          useWebGPU: 'webgpu',
          demuxUseWorker: true,
          networkDelay: 10,
          controlHtml: '<div style="color: red">这个是自定义HTML</div>',
        };
        console.log("JessibucaPro -> options: ", options);
        jessibucaPlayer[this._uid] = new window.JessibucaPro({
          ...options
        });
        this.geturl();
      },
      geturl() {
        // alert("xxx" + this.url)
        // jessibucaPlayer[this._uid].setNetworkDelayTime(10);
        jessibucaPlayer[this._uid].play(this.url).then(() => {
          console.log('play success')
          // jessibucaPlayer[this._uid].on('currentPts', (ts) => {

          //   if (this.datalist.length > 0) {

          //     //   console.log("未处理之前", this.datalist)
          //     this.datalist = this.datalist.filter((data) => {
          //       //       console.log(data.number + "---ts---", ts);
          //       return data.number >= ts;
          //     })
          //     console.log(ts + "处理后", this.datalist)
          //     let dataArray = [];
          //     if (this.datalist.length > 0) {
          //       this.datalist.find((data) => {
          //         if (data.number == ts) {
          //           let rectlist = {};
          //           rectlist.type = 'rect';
          //           rectlist.x = data.x;
          //           rectlist.y = data.y;
          //           rectlist.width = data.width;
          //           rectlist.height = data.height;
          //           rectlist.color = data.color;
          //           let namelist = {};
          //           namelist.type = 'text';
          //           namelist.text = data.name;
          //           namelist.x = data.x;
          //           namelist.y = data.y - 25;
          //           namelist.width = data.width;
          //           namelist.height = data.height;
          //           namelist.color = data.color;
          //           dataArray.push(rectlist);
          //           dataArray.push(namelist);


          //         }
          //       });
          //     }
          //     console.error("是否有数据", dataArray)
          //     if (dataArray.length > 0) {
          //       //     jessibucaPlayer[this._uid].clearContentToCanvas()
          //       jessibucaPlayer[this._uid].addContentToCanvas(dataArray)
          //     }

          //   }
          //s   })
        }).catch((e) => {
          console.log('play error', e)
        })
      },
      closeurl() {
        jessibucaPlayer[this._uid].destroy().then(() => {
          console.log('destroy success')
          this.initPro();
        }).catch((e) => {
          console.log('destroy error', e)
        })
      },
      initWebSocket: function() {
        // WebSocket与普通的请求所用协议有所不同，ws等同于http，wss等同于https
        var userId = store.getters.userInfo.id;
        var url = window._CONFIG['domianURL'].replace("https://", "wss://").replace("http://", "ws://") +
          "/websocket/" + userId;
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


        //处理订阅信息
        if (data.cmd == "video") {
          // jessibucaPlayer[this._uid].on("stats", function(s) {
          //   console.log("ts:视频显示时间(ms)", s.ts);
          //   console.log("视频解码内容:" + JSON.stringify(data));
          //   console.log("视频解码时间:" + s.dts + "下发视频解码时间:", data.number)
          //   //    jessibucaPlayer[this._uid].setNetworkDelayTime(Number(s));
          //   if ((s.dts - data.number) > 5000) {
          //     console.log("当前大于5秒抛弃不要", (s.dts - data.number))
          //     return;
          //   }
          //   console.log("~~当前小于5秒要~~", (s.dts - data.number))
          // })s
          if (this.number == 0 || this.number < data.number) {
            //   this.datalist = [...this.datalist, ...data.list];;
            this.number = data.number
            //   console.log(this.datalist)
            let datalist = data.list;
            let dataArray = [];

            for (let a = 0; a < datalist.length; a++) {

              if (this.url == datalist[a].url) {
                let rectlist = {};
                rectlist.type = 'rect';
                rectlist.x = datalist[a].x;
                rectlist.y = datalist[a].y;
                rectlist.width = datalist[a].width;
                rectlist.height = datalist[a].height;
                rectlist.color = datalist[a].color;
                let namelist = {};
                namelist.type = 'text';
                namelist.text = datalist[a].name;
                namelist.x = datalist[a].x;
                namelist.y = datalist[a].y - 25;
                namelist.width = datalist[a].width;
                namelist.height = datalist[a].height;
                namelist.color = datalist[a].color;
                dataArray.push(rectlist);
                dataArray.push(namelist);
              }

            }
            jessibucaPlayer[this._uid].addContentToCanvas(dataArray)
          } else {
            console.log("跳跃移除", data.number)
          }

        }

      },
      websocketclose: function(e) {
        console.log("connection closed ");
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
  }
</script>

<style scoped>
  .j-address-list-right-card-box {

    min-height: 400px;

   
  }
 .ant-card-body {
   padding: 0 !important;
 }

  #buttonsBox2 {
    height: 100%;
    min-height: 400px;
    background-color: #080808
  }
</style>
