<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="脚本名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="rosName">
              <a-input v-model="model.rosName" placeholder="请输入脚本名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="额外前置命令" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="beforePy">
              <a-input v-model="model.beforePy" placeholder="请输入额外前置命令"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本目录" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyPath">
              <a-input v-model="model.pyPath" placeholder="请输入脚本目录"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="rosFile">
              <j-upload v-model="model.rosFile"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="额外后置命令" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="endPy">
              <a-input v-model="model.endPy" placeholder="请输入额外后置命令"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remake">
              <a-input v-model="model.remake" placeholder="请输入脚本备注"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="脚本类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="pyType">
              <j-dict-select-tag type="list" v-model="model.pyType" dictCode="ros_py_type" placeholder="请选择脚本类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="执行顺序" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="sort">
              <a-input v-model="model.sort" placeholder="请输入执行顺序"  ></a-input>
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
    name: 'TabRosPythonForm',
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
          add: "/ros2/tabRosPython/add",
          edit: "/ros2/tabRosPython/edit",
          queryById: "/ros2/tabRosPython/queryById"
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