<template>
<div class="avatar">
    <el-container>
      <el-aside width="200px" height="840px">    
        <el-row>
         <el-button  @click="setSDKEvenet()" type="primary"> setSDKEvenet  </el-button>
         <el-button style="margin:0px" @click="setPlayerEvenet()" type="primary">setPlayerEvenet</el-button>
         <el-button style="margin:0px" @click="SetApiInfodialog = true" type="primary">SetApiInfo</el-button>
         <el-button style="margin:0px" @click="SetGlobalParamsdialog = true" type="primary">SetGlobalParams</el-button>
         <el-button style="margin:0px" @click="start()" type="primary">Start</el-button>
         <el-input
           type="textarea"
           placeholder="请输入内容"
           v-model="textarea"
           maxlength="100"
           show-word-limit
         >
         </el-input>
         <el-input v-model="input" placeholder="变声"></el-input>
         <el-radio v-model="nlp" :label="true">开启语义理解</el-radio>
         <el-radio v-model="nlp" :label="false">关闭语义理解</el-radio>
         <el-button style="margin:0px" @click="writeText()" type="primary">文本驱动</el-button>
         <!-- <el-button style="margin:0px" @click="writeAudio()" type="primary">语音驱动</el-button> -->
         <el-button style="margin:0px" @click="writeAudio()" v-if="recorderbutton==false" type="primary">开启录音</el-button>
         <el-button style="margin:0px" @click="stopRecord()" v-if="recorderbutton==true" type="primary">关闭录音</el-button>
         <el-button style="margin:0px" @click="stop()" type="primary">关闭连接</el-button>
        </el-row>
      </el-aside>

      <el-main style="padding:0px;">
        <div id="wrapper"></div>
      </el-main>

      <!--SetApiInfo悬浮框-->
      <el-dialog title="初始化SDK" :visible.sync="SetApiInfodialog">
        <el-form :model="form">
          <el-form-item label="Appid" :label-width="formLabelWidth">
            <el-input v-model="form.appid" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item label="ApiKey" :label-width="formLabelWidth">
            <el-input v-model="form.apikey" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item label="ApiSecret" :label-width="formLabelWidth">
            <el-input v-model="form.apisecret" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item label="SceneId" :label-width="formLabelWidth">
            <el-input v-model="form.sceneid" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item label="ServerUrl" :label-width="formLabelWidth">
            <el-input v-model="form.serverurl" autocomplete="off"></el-input>
          </el-form-item>
        </el-form>
        <div slot="footer" class="dialog-footer">
          <el-button @click="SetApiInfodialog = false">取 消</el-button>
          <el-button type="primary" @click="SetApiInfodialog = false,SetApiInfo2()">确 定</el-button>
        </div>
      </el-dialog>
      <!--SetGlobalParams悬浮框-->
      <el-dialog title="设置全局变量" :visible.sync="SetGlobalParamsdialog">
        <div style="text-align:center"><h3 >打断模式全局设置</h3></div>
          <el-form :model="setglobalparamsform" :label-width="formLabelWidth">
          <el-form-item label="视频协议">
            <el-tooltip class="item" effect="dark" content="支持webrtc/xrtc/rtmp(控制台打印视频流地址)" placement="right-start">
             <i class="el-icon-question"></i>
            </el-tooltip>
            <el-select v-model="setglobalparamsform.stream.protocol" placeholder="请选择视频流协议">
              <el-option label="xrtc" value="xrtc"></el-option>
              <el-option label="webrtc" value="webrtc"></el-option>
              <el-option label="rtmp" value="rtmp"></el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="透明背景">
            <el-tooltip class="item" effect="dark" content="仅支持xrtc协议" placement="right-start">
             <i class="el-icon-question"></i>
            </el-tooltip>
            <el-switch v-model="setglobalparamsform.stream.alpha"></el-switch>
          </el-form-item>
          <el-form-item label="形象ID" v-if="setglobalparamsform.avatar.avatar_id!=null">
            <el-input v-model="setglobalparamsform.avatar.avatar_id" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item  label="形象ID" v-if="setglobalparamsform.avatar.avatar_id==null">
            <el-input class="error" v-model="setglobalparamsform.avatar.avatar_id" autocomplete="off"></el-input>
          </el-form-item>    
          <el-form-item label="发音人（VCN）">
            <el-input v-model="setglobalparamsform.tts.vcn" autocomplete="off"></el-input>
          </el-form-item>
          <el-form-item label="背景图片">
            <el-radio-group v-model="setglobalparamsform.background.type">
              <el-radio label="url">URL</el-radio>
              <el-radio label="res_key">res_key(到交互平台-素材管理中获取)</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="背景数据">
            <el-input v-model="setglobalparamsform.background.data" autocomplete="off"></el-input>
          </el-form-item>
        </el-form>

        <el-form :model="form">
        </el-form>
        <div slot="footer" class="dialog-footer">
          <el-button @click="SetGlobalParamsdialog = false">取 消</el-button>
          <el-button type="primary" @click="SetGlobalParamsdialog = false,SetGlobalParams()">确 定</el-button>
        </div>
      </el-dialog>
    </el-container>

</div>

</template>

<script>
import AvatarPlatform,{PlayerEvents,SDKEvents} from "../vm-sdk/avatar-sdk-web_3.1.1.1011/index.js"
let avatarPlatform2 = new AvatarPlatform();
let recorder = avatarPlatform2.recorder;
if(recorder == null){
  recorder = avatarPlatform2.createRecorder();
}
export default {
    name: "avatarComponent",
    data(){
        return{
            data:"1",
            stream:{
                protocol:"xrtc",
                alpha:false,
            },
            avatar:{
                avatar_id:"130907001",
            },
            tts:{
                vcn:"x4_lingxiaoqi_assist",
            },
            SetApiInfodialog: false,
            SetGlobalParamsdialog: true,
            form: {
              appid:"c8224d46",
              apikey:"ee158cb8c4783636496edadfabba8fa8",
              apisecret:"ZWY3N2E2N2M3OTMwMDhjN2M4MmFmNDA2",
              sceneid:"77213753883627520",
              serverurl:"wss://avatar.cn-huadong-1.xf-yun.com/v1/interact",
            },
            setglobalparamsform:{
              stream:{
                protocol:"xrtc",
                fps:25,
                bitrate:1000000,
                alpha:false,
              },
              avatar:{
                avatar_id:"130907001",
                width:1920,
                height:1080,
                mask_region:"[0,0,1080,1920]",
                scale:1,
                move_h:0,
                move_v:0,
                audio_format:1,
              },
              tts:{
                vcn:"x5_sunwukong_flow",
                speed:50,
                pitch:50,
                volume:100,
                audio:{
                  sample_rate:16000,
                }
              },
              avatar_dispatch:{
                interactive_mode:1,
              },
              background: {
                enabled: true,
                type: 'res_key',
                data: '22SLM2teIw+aqR6Xsm2JbH6Ng310kDam2NiCY/RQ9n6dw47gMO+7gGUJfWWfkqD3IxsU/HMK1uJTTxxF2llcKSM4dlSdBy0Piag/DndHocqs32kTOwXUw6lkyggYQBXF0uwTv9jVFm1ZjZgSehV3kpx5RTvizZ9MqEI8lotCRvokC9HLI0pGfKtSmlKgCKL+OUoc9QI5HW3wLtYbLersumd4UCKEPk/uWAdKEh4ntSJiW2km8waGFsg/VSNFj5vaDK3LC4PxfsRvi1a2veZW7JUs/VOleE9wwgTH+A/oqPPcyksBY7aQ4TxYjvS9Qj9LtXkvOwttQMgPGwoxlqBEBhR/xLUwmecHkHzgjACFtxE=',
              },
              rules: {
                'stream.protocol': [
                  { required: true, message: '请选择视频流协议', trigger: 'change' }
                ],
                'avatar.avatar_id': [
                  { required: true, message: '请输入形象 ID', trigger: 'blur' }
                ],
                'tts.vcn': [
                  { required: true, message: '请输入发音人（VCN）', trigger: 'blur' }
                ],
                'background.type': [
                  { required: true, message: '请选择背景图片类型', trigger: 'change' }
                ],
                'background.data': [
                  { required: true, message: '请输入背景数据', trigger: 'blur' }
                ]
              }
            },
            formLabelWidth: '120px',
            textarea: '',
            input:"",
            recorderbutton:false,
            nlp:false,
        };
    },
    methods:{
        setSDKEvenet(){
            //绑定SDK事件
            avatarPlatform2.on(SDKEvents.connected,function(initResp){
                console.log("SDKEvent.connect:initResp:",initResp);
            })
            .on(SDKEvents.stream_start,function(){
                console.log("stream_start");
            })
            .on(SDKEvents.disconnected,function(err){
                console.log("SDKEvent.disconnected:",err)
                if (err) {
                  // 因为异常 而导致的断开！ 此处可以进行 提示通知等
                  console.error('ws link disconnected because of Error')
                  console.error(e.code, e.message, e.name, e.stack)
                }
            })
            this.open2("监听SDK事件成功");
        },
        setPlayerEvenet(){
            //绑定播放器事件
            player.on(PlayerEvents.play,function(){
                console.log("paly");
            })
            .on(PlayerEvents.playing,function(){
                console.log("playing")
            })
            this.open2("监听播放器事件成功")
        },
        SetApiInfo2(){
            //初始化SDK
            avatarPlatform2.setApiInfo({
            appId:this.form.appid,
            apiKey:this.form.apikey,
            apiSecret:this.form.apisecret,
            serverUrl:this.form.serverurl,
            sceneId:this.form.sceneid,

        })
          this.open2("初始化SDK成功")
        },
        SetGlobalParams(){
            avatarPlatform2.setGlobalParams({
                            stream:{
                                protocol:this.setglobalparamsform.stream.protocol,
                                alpha:this.setglobalparamsform.stream.alpha?1:0,
                            },
                            avatar:{
                                avatar_id:this.setglobalparamsform.avatar.avatar_id,
                                audio_format:2,
                                width:720,
                                height:1080,
                                scale:"1.0",
                                mask_region:"[0,0,1080,1920]",
                            },
                            tts:{
                                vcn:this.setglobalparamsform.tts.vcn,
                                speed:50,
                            },
                            background: {
                              // enabled: true,
                              type: this.setglobalparamsform.background.type,
                              data: this.setglobalparamsform.background.data,
                            }
                            
            })
            // 触发表单验证
            this.$refs.formRef.validate((valid) => {
            if (valid) {
              // 验证通过，处理表单提交逻辑
              console.log('表单提交成功', this.setglobalparamsform);
              this.open2("设置全局变量成功")
            } else {
              // 验证失败，提示用户
              console.log('表单验证失败，请检查必填项');
              return false;
            }
          });

        },
        start(){
            avatarPlatform2.start({wrapper:document.querySelector("#wrapper")})
            .catch((e)=>{
                console.error(e.code,e.message,e.name,e.stack)
            })
        },
        writeText(){
          const text = this.textarea;
          if(text!=null){
            avatarPlatform2.writeText(text,{
                nlp:this.nlp,
                tts:{
                  // vcn:this.input,
                  volume:100,
                }
            })
          }else{
            alert("内容不许为空")
          }

        },
        writeAudio(){
          recorder.startRecord(0,{
            nlp:true,
            avatar_dispatch: { 
              interactive_mode: 0
            }
          })
          //关闭录音按钮显示
          this.recorderbutton=true
          // avatarPlatform2.writeAudio(buffer,0,{
          //   nlp:true,
          // })
          // avatarPlatform2.writeAudio(buffer,1,{
          //   nlp:true,
          // })
          // avatarPlatform2.writeAudio(buffer,2,{
          //   nlp:true,
          // })
        },
        stopRecord(){
          recorder.stopRecord();
          //开启录音按钮显示
          this.recorderbutton=false
        },
        stop(){
          avatarPlatform2.stop();
        },
        open2(text) {
        this.$message({
          message: text,
          type: 'success'
        });
      },
        
    },
}


const player = avatarPlatform2.player








</script>

<style scoped>
*{
  margin:0px;
  padding:0px;
  box-sizing: border-box;
  border:none;
}
.el-button{
    width:200px;
    margin: 0px;
}
#wrapper{
    height: 840px;
    width: 1370.83px;
    border: 1px;
    border-color: #FFFFFF;
}
.error{
  border-block-color: red;
}

</style>