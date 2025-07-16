<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="英文标签名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="englishName">
              <a-input v-model="model.englishName" placeholder="请输入英文标签名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="中文标签名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="chainName">
              <a-input v-model="model.chainName" placeholder="请输入中文标签名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="RGB颜色代码" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="rgbColor">
              <a-input v-model="model.rgbColor" placeholder="请输入RGB颜色代码"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="CSS颜色代码" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="cssColor">
              <a-input v-model="model.cssColor" placeholder="请输入CSS颜色代码"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="示例图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="sendUrl" style="margin-bottom: 0;">
              <j-image-upload isMultiple  v-model="model.sendUrl" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="报警内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remark">
              <a-input v-model="model.remark" placeholder="请输入报警内容"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="语音播报内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spaceOne">
              <a-input v-model="model.spaceOne" placeholder="请输入语音播报内容"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="计数数量" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spaceTwo">
              <a-input type="number"  v-model="model.spaceTwo"  placeholder="请输入计数数量"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否推送第三方" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spaceThree">
                  <j-switch v-model="model.spaceThree"  ></j-switch>
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
    name: 'TabAiBaseForm',
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
          add: "/tab/tabAiBase/add",
          edit: "/tab/tabAiBase/edit",
          queryById: "/tab/tabAiBase/queryById"
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