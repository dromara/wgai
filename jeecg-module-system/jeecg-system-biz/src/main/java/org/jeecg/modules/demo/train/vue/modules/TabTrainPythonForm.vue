<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="脚本名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyName">
              <a-input v-model="model.pyName" placeholder="请输入脚本名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyUrl">
              <j-upload v-model="model.pyUrl"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="文件放置地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyPath">
              <a-input v-model="model.pyPath" placeholder="请输入文件放置地址"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyRemake">
              <a-input v-model="model.pyRemake" placeholder="请输入脚本备注"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyType">
              <j-dict-select-tag type="list" v-model="model.pyType" dictCode="py_type" placeholder="请选择脚本类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="执行顺序" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="sort">
              <a-input-number v-model="model.sort" placeholder="请输入执行顺序" style="width: 100%" />
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
    name: 'TabTrainPythonForm',
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
           pyName: [
              { required: true, message: '请输入脚本名称!'},
           ],
           sort: [
              { required: true, message: '请输入执行顺序!'},
           ],
        },
        url: {
          add: "/train/tabTrainPython/add",
          edit: "/train/tabTrainPython/edit",
          queryById: "/train/tabTrainPython/queryById"
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