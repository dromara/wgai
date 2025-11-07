<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="12">
            <a-form-model-item label="解码脚本" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyType">
             <j-dict-select-tag type="list" v-model="model.pyType" dictCode="py_type"
               placeholder="解码脚本" />
            </a-form-model-item>
          </a-col>
          <a-col :span="12">
            <a-form-model-item label="解码方式" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventTypes">
     <!--         <a-input v-model="model.eventTypes" placeholder="请输入解码方式"  ></a-input> -->
              
              <j-dict-select-tag type="list" v-model="model.eventTypes" dictCode="jm_type"
                placeholder="解码脚本" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="12">
            <a-form-model-item label="推理方式" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelJmType">
             <j-dict-select-tag  v-model="model.modelJmType" placeholder="请选择推理方式"
                               dictCode="model_type"/>
            </a-form-model-item>
          </a-col>
          
          <a-col :span="12">
            <a-form-model-item label="订阅名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="name">
              <a-input v-model="model.name" placeholder="订阅名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          
          
       
          
          <a-col :span="12">
            <a-form-model-item label="是否推送" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pushStatic" >
 <j-dict-select-tag type="list" v-model="model.pushStatic" dictCode="push_static"
   placeholder="是否推送" />
            </a-form-model-item>
          </a-col>
          <a-col :span="12">
            <a-form-model-item label="解析目录" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pathSave">
              <a-input v-model="model.pathSave" placeholder="请输入解析目录"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            
              <div style="color: red;text-align: center;"> 不设置时间将24h推送识别,设置开始时间-结束时间推送(24小时制)</div>
            
           </a-col> 
          <a-col :span="12">
            <a-form-model-item label="推开始时间" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="difyStartEnd">
            <a-input-number  v-model="model.difyStartEnd" placeholder="推送开始时间"  style="width: 100%"></a-input-number>
            </a-form-model-item>
          </a-col>
          
          <a-col :span="12">
            <a-form-model-item label="推结束时间" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="difyStartTime">
             <a-input-number v-model="model.difyStartTime" placeholder="推送结束时间"  style="width: 100%"></a-input-number>
            </a-form-model-item>
          </a-col>
       
          <a-col :span="24">
            <a-form-model-item label="订阅地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beginEventTypes">
               <a-input v-model="model.beginEventTypes" placeholder="请输入订阅地址"  ></a-input>
            </a-form-model-item>
            
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="订阅回调地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventUrl">
              <a-input v-model="model.eventUrl" placeholder="请输入订阅回调地址"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="设备编号" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="indexCode">
              <a-input v-model="model.indexCode" placeholder="请输入设备编号"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            
              <div style="color: red;text-align: center;"> 报警间隔需要大于录像时间，不然可能会导致报警无录像</div>
            
           </a-col> 
         <a-col :span="24">
            <a-form-model-item label="同类型报警间隔" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventNumber">
              <a-input v-model="model.eventNumber" placeholder="请输入同类型报警间隔"  ></a-input>
            </a-form-model-item>
          </a-col> 
        <!--  <a-col :span="24">
            <a-form-model-item label="报警消息" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventInfo">
              <a-input v-model="model.eventInfo" placeholder="请输入报警消息"  ></a-input>
            </a-form-model-item>
          </a-col> -->
        
         <!-- <a-col :span="24">
            <a-form-model-item label="推送状态" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pushStatic">
              <a-input-number v-model="model.pushStatic" placeholder="请输入推送状态" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="执行状态" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="runState">
              <a-input-number v-model="model.runState" placeholder="请输入执行状态" style="width: 100%" />
            </a-form-model-item>
          </a-col> -->
      
   <!--       <a-col :span="24">
            <a-form-model-item label="播报状态" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioStatic">
              <a-input-number v-model="model.audioStatic" placeholder="请输入播报状态" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="播报地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioId">
              <a-input v-model="model.audioId" placeholder="请输入播报地址"  ></a-input>
            </a-form-model-item>
          </a-col> -->
        <!--  <a-col :span="24">
            <a-form-model-item label="是否需要前置" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBegin">
              <a-input v-model="model.isBegin" placeholder="请输入是否需要前置"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="前置模型类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beginEventTypes">
              <a-input v-model="model.beginEventTypes" placeholder="请输入前置模型类型"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="前置模型内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beginName">
              <a-input v-model="model.beginName" placeholder="请输入前置模型内容"  ></a-input>
            </a-form-model-item>
          </a-col> -->
          
          <a-col :span="24">
            <a-form-model-item label="是否开启报警录像" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isRecording">
<!--              <j-switch v-model="model.isRecording"  ></j-switch> -->
              <j-dict-select-tag type="list" v-model="model.isRecording" dictCode="push_static"
                placeholder="是否开启报警录像" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            
              <div style="color: red;text-align: center;"> 分析录像会使上传延后上传，需等待分析结果完成后上传</div>
            
           </a-col> 
          <a-col :span="24"  v-if="model.isRecording==0">
              <a-form-model-item label="是否分析录像" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBegin">
                <j-dict-select-tag type="list" v-model="model.isBegin" dictCode="push_static"
                  placeholder="是否分析录像" />
              </a-form-model-item>
            </a-col>
      
              
          <a-col :span="24">
            <a-form-model-item label="是否本地保存录像" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="saveRecord">
    <!--          <j-switch v-model="model.saveRecord"  ></j-switch> -->
              <j-dict-select-tag type="list" v-model="model.saveRecord" dictCode="push_static"
                placeholder="是否本地保存录像" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24" v-if="model.isRecording==0">
            <a-form-model-item label="报警录像时间(秒/S)" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="recordTime">
              <a-input-number v-model="model.recordTime" placeholder="请输入报警录像时间" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          
        <a-col :span="24">
          <a-form-model-item label="是否保存本地报警" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="saveLocalhost">
            <!--          <j-switch v-model="model.saveRecord"  ></j-switch> -->
            <j-dict-select-tag type="list" v-model="model.saveLocalhost" dictCode="push_static" placeholder="是否保存本地报警" />
          </a-form-model-item>
        </a-col>
        
        <a-col :span="24">
          <a-form-model-item label="是否开启区域识别" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBy">
            <!--          <j-switch v-model="model.saveRecord"  ></j-switch> -->
            <j-dict-select-tag type="list" v-model="model.isBy" dictCode="push_static" placeholder="是否开启区域识别" />
          </a-form-model-item>
        </a-col>
       <!--   <a-col :span="24">
            <a-form-model-item label="是否保存图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="savePic">
              <j-switch v-model="model.savePic"  ></j-switch>
            </a-form-model-item>
          </a-col>  -->
          
          <a-col :span="24">
            <a-form-model-item label="备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remake">
              <a-input v-model="model.remake" placeholder="请输入备注"  ></a-input>
            </a-form-model-item>
          </a-col>
        </a-row>
      </a-form-model>
    </j-form-container>
  </a-spin>
</template>

<script>

  import { httpAction, getAction } from '@/api/manage'
  import { validateDuplicateValue } from '@/utils/util'

  export default {
    name: 'TabAiSubscriptionNewForm',
    components: {
    },
    props: {
      //表单禁用
      disabled: {
        type: Boolean,
        default: false,
        required: false
      }
    },
    data () {
      return {
        model:{
        pathSave:"D://error//videoTest",
        saveRecord:1,
        isRecording:1,
        pushStatic:1,
        isBegin:1,
        recordTime:10,
        saveLocalhost:1,
        isBy:1,
        
        },
        labelCol: {
          xs: { span: 24 },
          sm: { span: 5 },
        },
        wrapperCol: {
          xs: { span: 24 },
          sm: { span: 16 },
        },
        confirmLoading: false,
        validatorRules: {
           pyType: [
              { required: true, message: '请输入解码脚本!'},
           ],
           eventTypes: [
              { required: true, message: '请输入解码方式!'},
           ],
           name: [
              { required: true, message: '请输入订阅名称!'},
           ],
           beginEventTypes: [
              { required: true, message: '请输入订阅地址!'},
           ],
           eventUrl: [
              { required: true, message: '请输入订阅回调地址!'},
           ],
           indexCode: [
              { required: true, message: '请输入订阅设备编号!'},
           ],
           pathSave: [
              { required: true, message: '请输入解析!'},
           ],
           isRecording  : [
              { required: true, message: '请选择是否开启报警录像!'},
           ],
           eventNumber  : [
              { required: true, message: '请输入同类型报警间隔!'},
           ],
           pushStatic:[
             { required: true, message: '请选择是否推送!'},
           ],
           isBegin:[
              { required: true, message: '请选择是否分析录像!'},
           ]
        },
        url: {
          add: "/video/tabAiSubscriptionNew/add",
          edit: "/video/tabAiSubscriptionNew/edit",
          queryById: "/video/tabAiSubscriptionNew/queryById"
        }
      }
    },
    computed: {
      formDisabled(){
        return this.disabled
      },
    },
    created () {
       //备份model原始值
      this.modelDefault = JSON.parse(JSON.stringify(this.model));
    },
    methods: {
      add () {
        this.edit(this.modelDefault);
      },
      edit (record) {
        this.model = Object.assign({}, record);
        this.visible = true;
      },
      submitForm () {
        const that = this;
        // 触发表单验证
        this.$refs.form.validate(valid => {
          if (valid) {
            that.confirmLoading = true;
            let httpurl = '';
            let method = '';
            if(!this.model.id){
              httpurl+=this.url.add;
              method = 'post';
            }else{
              httpurl+=this.url.edit;
               method = 'put';
            }
            httpAction(httpurl,this.model,method).then((res)=>{
              if(res.success){
                that.$message.success(res.message);
                that.$emit('ok');
              }else{
                that.$message.warning(res.message);
              }
            }).finally(() => {
              that.confirmLoading = false;
            })
          }
         
        })
      },
    }
  }
</script>