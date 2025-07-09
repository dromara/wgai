<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="订阅ID" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="subId">
         <!--     <a-input v-model="model.subId" placeholder="请输入订阅ID"  ></a-input> -->
             <j-dict-select-tag type="list" v-model="model.subId" dictCode="tab_ai_subscription_new,name,id" placeholder="请选择订阅ID" />
             
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否需要前置" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBefor">
            <j-dict-select-tag type="list" v-model="model.isBefor" dictCode="push_static"
              placeholder="是否需要前置" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24"  v-if="model.isBefor==0">
            <a-form-model-item label="前置模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
              
           <j-search-select-tag  v-model="model.modelId" dict="tab_ai_model,ai_name,id" placeholder="请选择前置模型" />
           
            </a-form-model-item>
          </a-col>
          <a-col :span="24"  v-if="model.isBefor==0">
            <a-form-model-item label="前置识别内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelTxt">
         
              
              <j-search-select-tag  v-model="model.modelTxt" dict="tab_ai_base,chain_name,chain_name" placeholder="请选择前置模型" />
              
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="后置模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="nextMode">

   
              <j-search-select-tag  v-model="model.nextMode" dict="tab_ai_model,ai_name,id" placeholder="请选择前置模型" />
              
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备用1" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spareOne">
              <a-input v-model="model.spareOne" placeholder="请输入备用1"  ></a-input>
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
    name: 'TabAiVideoSettingForm',
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
         subId:[
             { required: true, message: '请选择视频!'},
          ],
         isBefor:[
             { required: true, message: '请选择是否需要前置!'},
          ],
          nextMode:[
              { required: true, message: '请选择是否需要前置!'},
           ]
          
        },
        url: {
          add: "/video/tabAiVideoSetting/add",
          edit: "/video/tabAiVideoSetting/edit",
          queryById: "/video/tabAiVideoSetting/queryById"
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