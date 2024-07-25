<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="设备名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="deviceName">
              <a-input v-model="model.deviceName" placeholder="请输入设备名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="设备URL" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="deivceUrl">
              <a-input v-model="model.deivceUrl" placeholder="请输入设备URL"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="设备唯一值" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="deviceUid">
              <a-input v-model="model.deviceUid" placeholder="请输入设备唯一值"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="用户名" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="username">
              <a-input v-model="model.username" placeholder="请输入用户名"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="密码" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pwd">
              <a-input v-model="model.pwd" placeholder="请输入密码"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否启用" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isState">
              <j-switch v-model="model.isState"  ></j-switch>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="token超时" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="tokenTime">
              <a-input-number v-model="model.tokenTime" placeholder="请输入token超时" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="厂家" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="deviceFac">
              <j-dict-select-tag type="list" v-model="model.deviceFac" dictCode="device_fac" placeholder="请选择厂家" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="文字转语音" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isAudio">
              <j-switch v-model="model.isAudio"  ></j-switch>
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
    name: 'TabAudioDeviceForm',
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
          add: "/audio/tabAudioDevice/add",
          edit: "/audio/tabAudioDevice/edit",
          queryById: "/audio/tabAudioDevice/queryById"
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