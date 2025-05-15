<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          
  <a-col :span="24">
            <a-form-model-item label="解码脚本" :labelCol="labelCol" :wrapperCol="wrapperCol"   prop="pyType">
              <j-dict-select-tag type="list" v-model="model.pyType" dictCode="py_type"
                placeholder="解码脚本" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否需要前置" :labelCol="labelCol" :wrapperCol="wrapperCol"   prop="isBegin">
              <j-dict-select-tag type="list" v-model="model.isBegin" dictCode="push_static"
                placeholder="是否需要前置" />
            </a-form-model-item>
          </a-col>

          <a-col :span="24" v-if="model.isBegin==0">
            <a-form-model-item label="前置模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beginEventTypes">
              <j-dict-select-tag type="list" v-model="model.beginEventTypes" dictCode="tab_ai_model,ai_name,id" placeholder="请选择前置模型" />
            </a-form-model-item>
            
          </a-col>
          
          <a-col :span="24"  v-if="model.isBegin==0">
            <a-form-model-item label="前置识别内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beginName">

              
                 <j-dict-select-tag type="list" v-model="model.beginName" dictCode="tab_ai_base,chain_name,chain_name" placeholder="请选择前置模型" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="订阅名称" :labelCol="labelCol" :wrapperCol="wrapperCol"  prop="name" >
             <a-input v-model="model.name" placeholder="请输入订阅名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="订阅类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventTypes">
              <j-multi-select-tag type="list_multi" v-model="model.eventTypes" dictCode="tab_ai_model,ai_name,id" placeholder="请选择订阅类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="订阅回调地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventUrl">
              <a-input v-model="model.eventUrl" placeholder="请输入订阅回调地址"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="同类型报警间隔" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventNumber">
              <a-input v-model="model.eventNumber" placeholder="请输入同类型报警间隔"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="报警消息" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="eventInfo">
              <a-input v-model="model.eventInfo" placeholder="请输入报警消息"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="订阅地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remake">
              <a-input v-model="model.remake" placeholder="请输入订阅地址"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="设备编号" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="indexCode">
              <a-input v-model="model.indexCode" placeholder="请输入设备编号"  ></a-input>
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="是否推送" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pushStatic" >
              <j-dict-select-tag type="list" v-model="model.pushStatic" dictCode="push_static"
                placeholder="请选择推送" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="是否播报" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioStatic"  >
              <j-dict-select-tag type="list" v-model="model.audioStatic" dictCode="push_static"
                placeholder="请选择推送" />
            </a-form-model-item>
          </a-col>
          
         
         <a-col :span="24" v-if="model.audioStatic==0">
           <a-form-model-item label="播报选择" :labelCol="labelCol" :wrapperCol="wrapperCol" >
             <j-dict-select-tag type="list" v-model="model.audioId" dictCode="tab_audio_device,device_name,id"
               placeholder="请选择播报名称" />
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
    name: 'TabAiSubscriptionForm',
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
          pyType:[
              { required: true, message: '请选择解码脚本!'},
           ],
          isBegin:[
              { required: true, message: '请选择是否需要前置!'},
           ],
           name:[
               { required: true, message: '请输入订阅名称!'},
            ],
          eventTypes:[
              { required: true, message: '请输入订阅类型!'},
           ],
          eventUrl:[
              { required: true, message: '请输入订阅回调地址!'},
           ],
           audioStatic:[
              { required: true, message: '请选择是否播报!'},
           ],
           pushStatic:[
              { required: true, message: '请选择是否推送!'},
           ],
           eventNumber:[
              { required: true, message: '请输入同类型报警间隔!'},
           ],
          remake:[
              { required: true, message: '请输入订阅地址!'},
           ],
        },
        url: {
          add: "/tab/tabAiSubscription/add",
          edit: "/tab/tabAiSubscription/edit",
          queryById: "/tab/tabAiSubscription/queryById"
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