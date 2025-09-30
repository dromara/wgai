<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="AI模型名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="aiName">
              <a-input v-model="model.aiName" placeholder="请输入AI模型名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="AI权重文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="aiWeights">
              <j-upload v-model="model.aiWeights"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="AI配置文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="aiConfig">
              <j-upload v-model="model.aiConfig"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="AIName文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="aiNameName">
              <j-upload v-model="model.aiNameName"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spareOne">
             <j-dict-select-tag  v-model="model.spareOne" placeholder="请选择模型类型"
                               dictCode="model_type"/>
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="识别方式" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelDifyType">
             <j-dict-select-tag  v-model="model.modelDifyType" placeholder="请选择识别类型"
                               dictCode="model_type"/>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="识别类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelDify">
             <j-dict-select-tag  v-model="model.modelDify" placeholder="请选择识别类型"
                               dictCode="dify_type"/>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="识别类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelJmType">
             <j-dict-select-tag  v-model="model.modelJmType" placeholder="请选择识别类型"
                               dictCode="jm_type"/>
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
    name: 'TabAiModelForm',
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
        },
        url: {
          add: "/tab/tabAiModel/add",
          edit: "/tab/tabAiModel/edit",
          queryById: "/tab/tabAiModel/queryById"
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