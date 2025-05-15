<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="语音类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioType">
              <j-dict-select-tag type="list" v-model="model.audioType" dictCode="audio_type" placeholder="请选择是语音类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否使用" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isStart">
              <j-dict-select-tag type="list" v-model="model.isStart" dictCode="run_state" placeholder="请选择是否使用" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="热词" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="hotWord">
              <j-upload v-model="model.hotWord"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="encoder权重" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="encoderPath">
              <j-upload v-model="model.encoderPath"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="decoder权重" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="decoderPath">
              <j-upload v-model="model.decoderPath"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="joiner权重" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="joinerPath">
              <j-upload v-model="model.joinerPath"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="token占词" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="tokenPath">
              <j-upload v-model="model.tokenPath"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="识别词类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modeLing">
              <a-input v-model="model.modeLing" placeholder="请输入识别词类型"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="识别模式" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="decodingMethod">
              <a-input v-model="model.decodingMethod" placeholder="请输入识别模式"  ></a-input>
            </a-form-model-item>
          </a-col>
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
    name: 'TabAuditSettingForm',
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
           // hotWord: [
           //    { required: true, message: '请输入热词!'},
           // ],
           encoderPath: [
              { required: true, message: '请输入encoder权重!'},
           ],
           decoderPath: [
              { required: true, message: '请输入decoder权重!'},
           ],
           joinerPath: [
              { required: true, message: '请输入joiner权重!'},
           ],
           tokenPath: [
              { required: true, message: '请输入token占词!'},
           ],
           modeLing: [
              { required: true, message: '请输入识别词类型!'},
           ],
           decodingMethod: [
              { required: true, message: '请输入识别模式!'},
           ],
           isStart:[ { required: true, message: '请选择是否使用!'},],
           audioType:[ { required: true, message: '请选择语音类型!'},]
        },
        url: {
          add: "/audio/tabAuditSetting/add",
          edit: "/audio/tabAuditSetting/edit",
          queryById: "/audio/tabAuditSetting/queryById"
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