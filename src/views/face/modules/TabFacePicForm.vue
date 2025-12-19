<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="所属模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
                <j-search-select-tag  v-model="model.modelId" dict="tab_ai_model,end_name,id,model_dify=20"  placeholder="请选择前置模型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="人脸名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="faceName">
              <a-input v-model="model.faceName" placeholder="请输入人脸名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="人脸图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="facePic">
              <j-image-upload isMultiple="true"  v-model="model.facePic" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="512维度数据" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="face512">
              <a-textarea v-model="model.face512" rows="4" placeholder="请输入512维度数据" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="3D维度数据" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="face3d">
              <a-textarea v-model="model.face3d" rows="4" placeholder="请输入3D维度数据" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="其他维度数据" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="faceOther">
              <a-textarea v-model="model.faceOther" rows="4" placeholder="请输入其他维度数据" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否标注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isRun">
              <j-dict-select-tag type="list" v-model="model.isRun" dictCode="push_static" placeholder="请选择是否标注" />
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
    name: 'TabFacePicForm',
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
         modelId: [
            { required: true, message: '请选择模型' }
         ],
              faceName: [
                { required: true, message: '请输入人脸名称' }
              ],
              facePic: [
                { required: true, message: '请上传人脸ZIP' }
              ],
         
            },
        url: {
          add: "/face/tabFacePic/add",
          edit: "/face/tabFacePic/edit",
          queryById: "/face/tabFacePic/queryById"
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