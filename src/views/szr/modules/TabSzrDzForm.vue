<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="数字人id" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrId">
              <j-search-select-tag v-model="model.szrId" dict="tab_szr_video,szr_name,id"  />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="数字人name" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrName">
              <a-input v-model="model.szrName" placeholder="请输入数字人name"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="数字人动作" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrTitle">
              <a-input v-model="model.szrTitle" placeholder="请输入数字人动作"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="数字人文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrFile">
              <j-image-upload isMultiple  v-model="model.szrFile" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="数字人帧率" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrFps">
              <a-input-number v-model="model.szrFps" placeholder="请输入数字人帧率" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="背景色" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrColor">
              <a-input v-model="model.szrColor" placeholder="请输入背景色"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="数字人标签" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="szrBq">
              <a-input v-model="model.szrBq" placeholder="请输入数字人标签"  ></a-input>
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
    name: 'TabSzrDzForm',
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
          add: "/szr/tabSzrDz/add",
          edit: "/szr/tabSzrDz/edit",
          queryById: "/szr/tabSzrDz/queryById"
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