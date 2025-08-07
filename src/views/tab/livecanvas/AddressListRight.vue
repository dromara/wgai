<template>
  <a-card class="j-address-list-right-card-box conthreelistri" :bordered="false">
    <div class="ceshi" style="width: 100%;height:50px;display: none;"> <input type="text" v-model="url">
      <button @click="geturl()">播放</button>
      <button @click="closeurl()">销毁</button>
    </div>
    </div>
  <div class="video-container">
    <div class="buttons-box" id="buttonsBox">


    </div>
    <div id="buttonsText" >
        {{videoInfo}}  
        <a-col :span="24">
          <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="摄像头id"  prop="videoId">
            <a-input  v-model="model.videoId" placeholder="摄像头id" :disabled="true" readonly="readonly"  ></a-input>
          </a-form-model-item>
        </a-col>
       <!-- <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="视频X坐标"  prop="videoStartx">
            <a-input  v-model="model.videoStartx" placeholder="视频起始X坐标":disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="视频Y坐标"  prop="videoStartx">
            <a-input  v-model="model.videoStartx" placeholder="视频起始Y坐标" :disabled="true" ></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="结束X坐标"  prop="videoEndx">
            <a-input  v-model="model.videoEndx"   placeholder="视频结束X坐标"  :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="结束y坐标"  prop="videoEndy">
            <a-input  v-model="model.videoEndy"   placeholder="视频结束y坐标"  :disabled="true"></a-input>
          </a-form-model-item>
        </a-col> -->
        
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="入侵X坐标"  prop="canvasStartx">
            <a-input  v-model="model.canvasStartx" placeholder="入侵X坐标":disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="入侵Y坐标"  prop="canvasStarty">
            <a-input  v-model="model.canvasStarty" placeholder="入侵Y坐标" :disabled="true" ></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="入侵区域宽度"  prop="canvasWidth">
            <a-input  v-model="model.canvasWidth" placeholder="入侵区域宽度"  :disabled="true"></a-input>
          </a-form-model-item> 
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="入侵区域高度"  prop="canvasHeight">
            <a-input  v-model="model.canvasHeight" placeholder="入侵区域高度"  :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
  <a-col :span="24">
    <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="入侵模型"  prop="videoJson">
     <j-dict-select-tag v-model="model.spareTwo"  :style="{width:'300px'}" dictCode="tab_ai_model,ai_name,id,spare_one='2'" placeholder="入侵模型" />
    
    </a-form-model-item>
  </a-col>
        <a-col :span="24">
          <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="入侵类型"  prop="videoJson">
          <j-multi-select-tag type="list_multi" :style="{width:'300px'}" v-model="model.videoJson" dictCode="tab_ai_base,chain_name,english_name" placeholder="请选择入侵类型" />
        
          </a-form-model-item>
        </a-col>
   
       <a-col :span="24">
       <a-button type="primary" style="margin: 8px;
    margin-left: 35%;" @click="startRecording" >确认配置</a-button>
       <a-button type="danger" @click="stopRecording" >取消配置</a-button>
       </a-col> 
        
    </div>
   <canvas
            ref="canvas"
            @mousedown="startDrawing"
            @mouseup="stopDrawing"
            @mousemove="draw"
          ></canvas>
   </div>
    <div class="conthreelistdw" v-if="rectangle">
        <div>入侵区域范围<label>:</label></div> 
		<div>
			<p>Coordinates:{{ rectangle}}</p>
			<p>Start: ({{ rectangle.startX }}, {{ rectangle.startY }})</p>
			<p>End: ({{ rectangle.endX }}, {{ rectangle.endY }})</p>
			 <p>计算结果:{{ resultRect}}</p>
		</div>
    </div>
  </a-card>
</template>

<script>
 import { httpAction, getAction } from '@/api/manage'
  import store from '@/store/'
  import Vue from 'vue'
  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'
  
  
  let jessibucaPlayer = {};
  export default {
    name: 'AddressListRight',

    components: {},
    props: ['value'],
    data() {
      return {
       model: {
             canvasStartx: '',
             canvasStarty: '',
             canvasWidth: '',
             canvasHeight: '',
             videoId: ''
           },
        description: '用户信息',
        strokeStyle:'red',
        cardLoading: true,
        positionInfo: {},
        _uid: 0,
        url: 'ws://192.168.0.252:8888/rtp/34020000001320000009_34020000001310000001.live.flv',
        heartbeatInterval: null,
        number: 0,
        datalist: [],
         drawing: false,
              context: null,
              rectangle: null,
          videoInfo:null   ,
          resultRect:null,
          videoId:null
      }
    },
    watch: {
      value: {
        immediate: true,
        handler(url) {
          if(url){
            console.log(url)
            this.url = url.split(";")[0];
            this.model.videoId = url.split(";")[1];
            this.videoId=this.model.videoId;
            console.log( this.videoId )
            this.closeurl();
          }
      
          // this.initPro();

        }
      },
    },
    created() {},
    mounted() {
      //初始化websocket
     // this.initWebSocket();
      this.initPro();


    },
    destroyed: function() { // 离开页面生命周期函数
   //   this.websocketclose();
      this.closeurl();
    },
    methods: {
    edit (record) {
        this.model = Object.assign({}, record);
        console.log("1111111111")  
        let that=this;
        getAction("/tab/tabAiModelBund/queryById", {id:this.model.videoId}).then((res) => {
          if (res.success) {
          console.log(res)
           that.url=res.result.sendUrl;
           that.closeurl();
           that.initPro();
          } else {
                 console.log(res)
          }
        })
      },
      initPro() {
        let that=this;
        let options = {
          container: document.getElementById('buttonsBox'),
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
          showPerformance: true, // 显示性能
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
          heartTimeoutReplayUseLastFrameShow: true,
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
          // useCanvasRender: 'canvas',
          useWebGPU: 'webgpu',
          demuxUseWorker: true,
          networkDelay: 10,
          controlHtml: '<div style="color: red">这个是自定义HTML</div>',
        };
        console.log("JessibucaPro -> options: ", options);
        jessibucaPlayer[this._uid] = new window.JessibucaPro({
          ...options
        });
         jessibucaPlayer[this._uid] .on('load',function(){
           console.log("xxxxxx")
             that.geturl();
             that.setupCanvas();
         })
      
      },
      geturl(){
        // alert("xxx" + this.url)
        // jessibucaPlayer[this._uid].setNetworkDelayTime(10);
        jessibucaPlayer[this._uid].play(this.url).then(() => {
          
          this.videoInfo=jessibucaPlayer[this._uid].getVideoInfo();
          console.log('play success',info)
       
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
        console.log("connection closed (" + e.code + ")");
      },

      websocketSend(text) {
        // 数据发送
        try {
          this.websock.send(text);
        } catch (err) {
          console.log("send failed ()");
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
 setupCanvas() {
   console.log("进入")
      const canvas = this.$refs.canvas;
      const player = document.getElementById('buttonsBox');
      canvas.width = player.clientWidth;
      canvas.height = player.clientHeight;
      this.context = canvas.getContext("2d");
    },
    startDrawing(event) {
      this.drawing = true;
      this.rectangle = {
        startX: event.offsetX,
        startY: event.offsetY,
        endX: event.offsetX,
        endY: event.offsetY,
      };
    },
    stopDrawing() {
      this.drawing = false;
      console.log(this.rectangle);
      console.log( this.videoInfo);
      let videoWidth=this.videoInfo.width;//视频宽高
      let videoHeight=this.videoInfo.height;
      
      let startX=this.rectangle.startX;//绘制宽高
      let startY=this.rectangle.startY;
      let endX=this.rectangle.endX;//绘制宽高
      let endY=this.rectangle.endY;
      let cavasWidth=endX-startX;
      let cavasHeight=endY-startY;
      // if(videoWidth>=640&&videoHeight>=640){
      //   let videoW=videoWidth/640;
      //   let videoH=videoHeight/640;
      //   let xAi=startX*videoW;
      //   let yAi=startY*videoW;
      //   let wAi=startY*cavasWidth;
      //   let hAi=startY*cavasHeight;
        
      // }else if(videoWidth<640&&videoHeight<640){
        
      // }else if(videoWidth>=640&&videoHeight<640){
        
      // }else if(videoWidth<640&&videoHeight>=640){
       
      // }
      let modelVal= this.calculateScaledDimensions(640, 640, startX, startY, endX, endY, videoWidth, videoHeight);
      
      this.model.canvasStartx=modelVal.canvasStartx;
      this.model.canvasStarty=modelVal.canvasStarty;
      this.model.canvasWidth=modelVal.canvasWidth;
      this.model.canvasHeight=modelVal.canvasHeight;
      this.model.videoId=this.videoId;
      //this.clearDrawing();
      let dataArray=[];
      let rectlist = {};
        rectlist.type = 'rect';
        rectlist.x = modelVal.canvasStartx;
        rectlist.y = modelVal.canvasStarty;
        rectlist.width = modelVal.canvasWidth;
        rectlist.height = modelVal.canvasHeight;
        rectlist.color ="green";
        dataArray.push(rectlist);
         
        jessibucaPlayer[this._uid].addContentToCanvas(dataArray)
    },
    calculateScaledDimensions(originalWidth, originalHeight, startX, startY, endX, endY, randomWidth, randomHeight) {
    // 计算宽度和高度的缩放因子
     const scaleFactorX = randomWidth / originalWidth;
     const scaleFactorY = randomHeight / originalHeight;
   
     // 缩放后的矩形尺寸和位置
     const newStartX = startX * scaleFactorX;
     const newStartY = startY * scaleFactorY;
     const newRectWidth = (endX - startX) * scaleFactorX;
     const newRectHeight = (endY - startY) * scaleFactorY;
   
     // return {
     //   canvasStartx: newStartX,
     //   canvasStarty: newStartY,
     //   canvasWidth: newRectWidth,
     //   canvasHeight: newRectHeight
     // };
     return {
       canvasStartx: startX,
       canvasStarty: startY,
       canvasWidth: (endX - startX),
       canvasHeight: (endY - startY)
     };
    },
    draw(event) {
      if (!this.drawing) return;

      const x = event.offsetX;
      const y = event.offsetY;

      this.rectangle.endX = x;   
      this.rectangle.endY = y;

      this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
      this.context.strokeStyle = this.strokeStyle; // 设置线条颜色
      this.context.strokeRect(
        this.rectangle.startX,
        this.rectangle.startY,
        x - this.rectangle.startX,
        y - this.rectangle.startY
      );
    },clearDrawing() {
          if (!this.context) return;
          this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
          this.rectangle = null;
    },
        startRecording(){ //确认配置
        
            let that=this;
            if(this.model.id){
              console.log("model.id",this.model.id)
              this.$confirm({
                title: "确认提交修改配置吗",
                content: "确认提交修改配置吗!",
                onOk: function() {
                  let httpurl = '';
                  let method = '';
                  //  debugger;
                  httpurl='/video/tabVideoUtil/edit';
                  method = 'post';
              
                  httpAction(httpurl, that.model,method).then((res) => {
                    if (res.success) {
                      that.$message.success(res.message);
                      that.$emit('ok');
                    } else {
                      that.$message.warning(res.message);
                    }
                  }).finally(() => {
                    that.confirmLoading = false;
                  })
              
                }
              });
            }else{
              this.$confirm({
                title: "确认提交配置吗",
                content: "确认提交配置吗!",
                onOk: function() {
                  let httpurl = '';
                  let method = '';
                  //  debugger;
                  httpurl='/video/tabVideoUtil/add';
                  method = 'post';
              
                  httpAction(httpurl, that.model,method).then((res) => {
                    if (res.success) {
                      that.$message.success(res.message);
                      that.$emit('ok');
                    } else {
                      that.$message.warning(res.message);
                    }
                  }).finally(() => {
                    that.confirmLoading = false;
                  })
              
                }
              });
            }
          
        },
        stopRecording(){ //取消配置
                const that = this;
          this.$confirm({
            title: "确认取消配置吗？",
            content: "确认取消配置吗!",
            onOk: function() {
              
                  that.$message.success("取消成功");
               
          
            }
          });
        }
    }
  }
</script>

<style scoped>
  .ant-card-body {
    height: 100%;
    min-height: 740px;
	padding: 10px;
	box-sizing: border-box;
  }

  #buttonsBox {
    height: 640px;
    width: 640px;
    min-height: 400px;
    /* background-color: #080808 */
        float: left;
  }
  #buttonsText{
    float: left;
    width:calc(100% - 640px)
  }
  .video-container {
    position: relative;
  }
  canvas {
    position: absolute;
    top: 0;
    left: 0;
/*    background: #4c4c4c; */
  }
  

</style>
<style>
.conthreelistri{margin:10px 0;box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);border-radius: 10px;background: linear-gradient(to top, #ffffff, #f5faff) !important;height:calc(100vh - 173px);min-height: 740px!important;border-radius: 10px!important;overflow: hidden;}
.conthreelistri .ant-card-body{padding: 10px;height: 100%;box-sizing: border-box;}
.conthreelistri #buttonsText .ant-form-item-label,.conthreelistdw div:nth-child(1){width: 120px;float: left;}
.conthreelistri #buttonsText .ant-form-item-control-wrapper,.conthreelistdw div:nth-child(2){width: calc(100% - 120px);float: left;}
.conthreelistri #buttonsText .ant-select-selection{background-color: #f5f5f5;}
.conthreelistdw div:nth-child(1){text-align: right;color:#000;}
.conthreelistdw div:nth-child(1) label{content: ':';position: relative;top: -0.5px;margin: 0 8px 0 2px;}
.conthreelistdw div:nth-child(2){color:#0364ff;}
.conthreelistri .ant-btn-primary{background-color: #2f51ff;border-color: #2f51ff;}
.conthreelistri .ant-btn-danger{color: #ff4d4f;border-color: #ff4d4f;}
.conthreelistdw{width: calc(100% - 640px);float: left;padding: 10px;box-sizing: border-box;}
</style>