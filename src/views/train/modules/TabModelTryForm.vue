<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="模型名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelName">
              <a-input v-model="model.modelName" placeholder="请输入模型名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型图标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelPic">
              <j-image-upload v-model="model.modelPic"  multiple=false  number=1 ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型简介" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelTitle">
           <a-input v-model="model.modelTitle" placeholder="请输入模型简介"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelType">
         <!--     <a-input v-model="model.modelType" placeholder="请输入模型类型"  ></a-input> -->
              <j-dict-select-tag  v-model="model.modelType" placeholder="请选择模型类型"
                                dictCode="model_type"/>
            </a-form-model-item>
          </a-col>
        <!--  <a-col :span="24">
            <a-form-model-item label="图片数量" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="picNumber">
              <a-input v-model="model.picNumber" placeholder="请输入图片数量"  ></a-input>
            </a-form-model-item>
          </a-col> -->
       <!--   <a-col :span="24">
            <a-form-model-item label="标签文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="txtTitle">
              <j-upload v-model="model.txtTitle"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="标签内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="txtInfo">
              <a-input v-model="model.txtInfo" placeholder="请输入标签内容"  ></a-input>
            </a-form-model-item>
          </a-col> -->
         <a-col :span="24">
           <a-form-model-item label="标签内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="txtInfo">
             <a-input v-model="model.txtInfo" placeholder="请输入标签内容"  ></a-input>
           </a-form-model-item>
         </a-col>
          <a-col :span="24">
            <a-form-model-item label="图片文件(ZIP)" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="picUrl">
              <j-upload v-model="model.picUrl"  multiple=false  number=1 ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="图片简称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="picName">
              <a-input v-model="model.picName" placeholder="请输入图片简称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否操作图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="updatePic">
              <j-switch v-model="model.updatePic"  ></j-switch>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否覆盖图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isInsert">
              <j-switch v-model="model.isInsert"  ></j-switch>
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
    name: 'TabModelTryForm',
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
          add: "/train/tabModelTry/add",
          edit: "/train/tabModelTry/edit",
          queryById: "/train/tabModelTry/queryById"
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