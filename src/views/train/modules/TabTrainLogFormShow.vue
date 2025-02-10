<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container >
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24" :disabled="formDisabled">
            <a-form-model-item label="模型名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
              <j-dict-select-tag type="list" v-model="model.modelId" dictCode="tab_model_try,model_name,id" placeholder="请选择模型名称" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="日志内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainLog">
            <!-- <a-input v-model="model.trainLog" placeholder="请输入日志内容"  ></a-input>
           {{ model.trainLog }} -->
              <j-code-editor
               ref="editor"
                language="Python"
                v-model="cnm"
                :fullScreen="true"
                :lineNumbers="true"
                style="min-height: 100px"/>
                 
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
  import JCodeEditor from '@/components/jeecg/JCodeEditor'
  export default {
    name: 'TabTrainLogFormShow',
    components: {
       JCodeEditor
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
         cnm:'xxxxxxxxxxxx',
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
          add: "/train/tabTrainLog/add",
          edit: "/train/tabTrainLog/edit",
          queryById: "/train/tabTrainLog/queryById"
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
        this.$refs.editor.setCodeContent(this.model.trainLog);
        console.log("输出",this.model)
      
       // this.visible = true;
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