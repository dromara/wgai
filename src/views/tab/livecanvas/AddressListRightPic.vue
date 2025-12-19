<template>
  <a-card class="j-address-list-right-card-box conthreelistri" :bordered="false">
    <div class="ceshi" style="width: 100%;height:50px;display: none;"> <input type="text" v-model="url">
      <button @click="geturl()">播放</button>
      <button @click="closeurl()">销毁</button>
    </div>
    </div>
    <div class="video-container">
      <div class="buttons-box" id="buttonsBox">
        <img :src="url" width="100%" height="100%" @load="onImgLoad" ref="myImg"><img>

      </div>
      <div id="buttonsText">
        {{videoInfo}}
        <a-col :span="24" :readonly="true">
          <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="订阅摄像头" prop="videoId">
            <!--            <a-input  v-model="model.videoId" placeholder="摄像头id" :disabled="true" readonly="readonly"  ></a-input> -->

            <j-dict-select-tag type="list"   :readonly="true" style="width: 100%;" v-model="model.videoId"
              dictCode="tab_ai_subscription_new,name,id" placeholder="请选择订阅ID" />

          </a-form-model-item>
        </a-col>

        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="原始尺寸" prop="videoStart">
            <a-input v-model="model.videoStart" placeholder="原始尺寸" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="原始X坐标" prop="videoStartx">
            <a-input v-model="model.videoStartx" placeholder="原始起始X坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="原始Y坐标" prop="videoStartx">
            <a-input v-model="model.videoStarty" placeholder="原始起始Y坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="结束X坐标" prop="videoEndx">
            <a-input v-model="model.videoEndx" placeholder="原始结束X坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="结束y坐标" prop="videoEndy">
            <a-input v-model="model.videoEndy" placeholder="原始结束y坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>


        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="设置X坐标" prop="canvasStartx">
            <a-input v-model="model.canvasStartx" placeholder="设置X坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="设置Y坐标" prop="canvasStarty">
            <a-input v-model="model.canvasStarty" placeholder="设置Y坐标" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="设置区域宽度" prop="canvasWidth">
            <a-input v-model="model.canvasWidth" placeholder="设置区域宽度" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <a-col :span="12">
          <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="设置区域高度" prop="canvasHeight">
            <a-input v-model="model.canvasHeight" placeholder="设置区域高度" :disabled="true"></a-input>
          </a-form-model-item>
        </a-col>
        <!--  <a-col :span="24">
    <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="设置模型"  prop="videoJson">
     <j-dict-select-tag v-model="model.spareTwo"  :style="{width:'300px'}" dictCode="tab_ai_model,ai_name,id,spare_one='2'" placeholder="设置模型" />
    
    </a-form-model-item>
  </a-col>
        <a-col :span="24">
          <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="设置类型"  prop="videoJson">
          <j-multi-select-tag type="list_multi" :style="{width:'300px'}" v-model="model.videoJson" dictCode="tab_ai_base,chain_name,english_name" placeholder="请选择设置类型" />
        
          </a-form-model-item>
        </a-col> -->

        <a-col :span="24">
          <a-button type="primary" style="margin: 8px; margin-left: 35%;" @click="edit(null)">重新获取画面</a-button>
          <a-button type="primary" style="margin: 8px; " @click="startRecording">确认配置</a-button>
          <a-button type="danger" style="color: white;" @click="stopRecording">取消配置</a-button>
        </a-col>

      </div>
      <canvas ref="canvas" @mousedown="startDrawing" @mouseup="stopDrawing" @mousemove="draw"></canvas>
    </div>
    <div class="conthreelistdw" v-if="rectangle">
      <div>设置区域范围<label>:</label></div>
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
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  import store from '@/store/'
  import Vue from 'vue'
  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'


  let jessibucaPlayer = {};
  export default {
    name: 'AddressListRightPic',

    components: {},
    props: ['value'],
    data() {
      return {
        model: {
          canvasStartx: 0,
          canvasStarty: 0,
          canvasWidth: 0,
          canvasHeight: 0,
          videoId: "",
          videoStarty: 0,
          videoStartx: 0,
          videoStarty: 0,
          videoEndx: 0,
          videoEndy: 0,
          videoStart: 0,
          spareTwo: "pic"
        },
        description: '用户信息',
        strokeStyle: 'red',
        cardLoading: true,
        positionInfo: {},
        _uid: 0,
        url: '/logo.png',
        heartbeatInterval: null,
        number: 0,
        datalist: [],
        drawing: false,
        context: null,
        rectangle: null,
        videoInfo: null,
        resultRect: null,
        videoId: null
      }
    },
    watch: {
      value: {
        immediate: true,
        handler(url) {
          if (url) {
            console.log(url)
            console.log("url");
          }

          // this.initPro();

        }
      },
    },
    created() {},
    mounted() {
      //初始化websocket
      // this.initWebSocket();
      //   this.initPro();
      //     this.geturl();
      this.setupCanvas();

    },
    methods: {
      onImgLoad(e) {
        const img = e.target
        this.model.videoStartx = 0;
        this.model.videoStarty = 0;
        this.model.videoEndx = img.naturalWidth;
        this.model.videoEndy = img.naturalHeight;
        this.model.videoStart = img.naturalWidth + "X" + img.naturalHeight;
        console.log("原始宽度:", img.naturalWidth)
        console.log("原始高度:", img.naturalHeight)
      },
      edit(record) {
        this.ImageUrl = `${window._CONFIG['domianURL']}/sys/common/static/`;
        if (record != null) {
          this.model.videoId = record.id;
        }
        console.log("1111111111", )
        let that = this;
        getAction("/video/tabVideoUtil/getVideoPic", {
          id: this.model.videoId
        }).then((res) => {
          if (res.success) {
            console.log(res)
            this.model = res.result;
            this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
            this.context.strokeStyle = this.strokeStyle; // 设置线条颜色
            this.context.strokeRect(
              this.model.canvasStartx,
              this.model.canvasStarty,
              Number(this.model.canvasWidth),
              Number(this.model.canvasHeight),
            );
            console.log(         Number(this.model.canvasStartx)+Number(this.model.canvasWidth),   Number(this.model.canvasStarty)+Number(this.model.canvasHeight));
            that.$message.success("获取成功！");

          } else {
            console.log(res)
            that.$message.warning("");

          }
          getAction("/video/tabAiSubscriptionNew/getVideoPic", {
            id: this.model.videoId
          }).then((res) => {
            if (res.success) {
              console.log(res)
              that.url = this.ImageUrl + res.result
              that.$message.success("获取成功！");

            } else {
              console.log(res)
              that.$message.warning("获取失败");

            }
          });
        });

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
        let startX = this.rectangle.startX; //绘制宽高
        let startY = this.rectangle.startY;
        let endX = this.rectangle.endX; //绘制宽高
        let endY = this.rectangle.endY;
        let cavasWidth = endX - startX;
        let cavasHeight = endY - startY;
        this.model.canvasStartx = startX;
        this.model.canvasStarty = startY;
        this.model.canvasWidth = cavasWidth;
        this.model.canvasHeight = cavasHeight;

        //this.clearDrawing();
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
      },
      clearDrawing() {
        if (!this.context) return;
        this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
        this.rectangle = null;
      },
      startRecording() { //确认配置

        let that = this;

        console.log("model.id", this.model.videoId)
        this.$confirm({
          title: "确认提交修改配置吗",
          content: "确认提交修改配置吗!",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl = '/video/tabVideoUtil/saveBox';
            method = 'post';

            httpAction(httpurl, that.model, method).then((res) => {
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


      },
      stopRecording() { //取消配置
        const that = this;
        this.$confirm({
          title: "确认取消配置吗？",
          content: "确认取消配置吗!",
          onOk: function() {
            that.clearDrawing();
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

  #buttonsText {
    float: left;
    width: calc(100% - 640px)
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
  .conthreelistri {
    margin: 10px 0;
    box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);
    border-radius: 10px;
    background: linear-gradient(to top, #ffffff, #f5faff) !important;
    height: calc(100vh - 173px);
    min-height: 740px !important;
    border-radius: 10px !important;
    overflow: hidden;
  }

  .conthreelistri .ant-card-body {
    padding: 10px;
    height: 100%;
    box-sizing: border-box;
  }

  .conthreelistri #buttonsText .ant-form-item-label,
  .conthreelistdw div:nth-child(1) {
    width: 120px;
    float: left;
  }

  .conthreelistri #buttonsText .ant-form-item-control-wrapper,
  .conthreelistdw div:nth-child(2) {
    width: calc(100% - 120px);
    float: left;
  }

  .conthreelistri #buttonsText .ant-select-selection {
    background-color: #f5f5f5;
  }

  .conthreelistdw div:nth-child(1) {
    text-align: right;
    color: #000;
  }

  .conthreelistdw div:nth-child(1) label {
    content: ':';
    position: relative;
    top: -0.5px;
    margin: 0 8px 0 2px;
  }

  .conthreelistdw div:nth-child(2) {
    color: #0364ff;
  }

  .conthreelistri .ant-btn-primary {
    background-color: #2f51ff;
    border-color: #2f51ff;
  }

  .conthreelistri .ant-btn-danger {
    color: #ff4d4f;
    border-color: #ff4d4f;
  }

  .conthreelistdw {
    width: calc(100% - 640px);
    float: left;
    padding: 10px;
    box-sizing: border-box;
  }
</style>