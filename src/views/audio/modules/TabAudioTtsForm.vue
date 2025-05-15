<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="语音类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioType">
              <j-dict-select-tag type="list" v-model="model.audioType" dictCode="audio_type" placeholder="请选择语音类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="语音名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioName">
              <a-input v-model="model.audioName" placeholder="请输入语音名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioModel">
              <j-upload v-model="model.audioModel"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="token文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioToken">
              <j-upload v-model="model.audioToken"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="lexicon文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioLexicon">
              <j-upload v-model="model.audioLexicon"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="Dict目录" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="dictDir">
              <j-upload v-model="model.dictDir"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="fsts多文件地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="ruleFasts">
              <j-upload v-model="model.ruleFasts"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="线程数" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="threadNum">
              <a-input-number v-model="model.threadNum" placeholder="请输入线程数" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="音色下标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioSid">
              <a-input-number v-model="model.audioSid" placeholder="请输入音色下标" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="语音速度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioSpeed">
              <a-input-number v-model="model.audioSpeed" placeholder="请输入语音速度" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="保存地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="savePath">
              <a-input v-model="model.savePath" placeholder="请输入保存地址"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="文本转语音内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="audioText">
              <a-textarea v-model="model.audioText" rows="4" placeholder="请输入文本转语音内容" />
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
    name: 'TabAudioTtsForm',
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
           audioType: [
              { required: true, message: '请输入语音类型!'},
           ],
           audioModel: [
              { required: true, message: '请输入模型文件!'},
           ],
           audioToken: [
              { required: true, message: '请输入token文件!'},
           ],
           audioLexicon: [
              { required: true, message: '请输入lexicon文件!'},
           ],
           ruleFasts: [
              { required: true, message: '请输入fsts多文件地址!'},
           ],
           threadNum: [
              { required: true, message: '请输入线程数!'},
           ],
           audioSid: [
              { required: true, message: '请输入音色下标!'},
           ],
           audioSpeed: [
              { required: true, message: '请输入语音速度!'},
           ],
        },
        url: {
          add: "/audio/tabAudioTts/add",
          edit: "/audio/tabAudioTts/edit",
          queryById: "/audio/tabAudioTts/queryById"
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