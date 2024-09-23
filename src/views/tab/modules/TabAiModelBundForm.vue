<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="识别名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spaceTwo">
              <a-input v-model="model.spaceTwo" placeholder="请输入识别名称"></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="模型名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelName">
              <j-dict-select-tag type="list" v-model="model.modelName" dictCode="tab_ai_model,ai_name,id"
                placeholder="请选择模型名称" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="识别类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spaceOne">
              <j-dict-select-tag type="list" v-model="model.spaceOne" dictCode="identify_status"
                placeholder="请选择识别类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="图片 | 视频流地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="sendUrl">
              <a-input v-model="model.sendUrl" placeholder="请输入图片或者视频地址"></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24" v-if="model.spaceOne==0">
            <a-form-model-item label="图片存放地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="saveUrl">
              <j-upload isMultiple v-model="model.saveUrl"></j-upload>
              <span>注：输入图片地址就不用上传图片会自动保存</span>
            </a-form-model-item>

          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remake">
              <a-input v-model="model.remake" placeholder="请输入备注"></a-input>
            </a-form-model-item>
          </a-col>
        </a-row>
      </a-form-model>
    </j-form-container>
  </a-spin>
</template>

<script>
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  import {
    validateDuplicateValue
  } from '@/utils/util'

  export default {
    name: 'TabAiModelBundForm',
    components: {},
    props: {
      //表单禁用
      disabled: {
        type: Boolean,
        default: false,
        required: false
      }
    },
    data() {
      return {
        urlIsrequired: false,
        model: {},
        labelCol: {
          xs: {
            span: 24
          },
          sm: {
            span: 5
          },
        },
        wrapperCol: {
          xs: {
            span: 24
          },
          sm: {
            span: 16
          },
        },
        confirmLoading: false,
        validatorRules: {
          spaceOne: [{
            required: true,
            message: '请选择识别类型!'
          }, ],
          sendUrl: [{
            required: this.urlIsrequired,
            message: '请输入图片地址或者流地址!'
          }, ],
        },
        url: {
          add: "/tab/tabAiModelBund/add",
          edit: "/tab/tabAiModelBund/edit",
          queryById: "/tab/tabAiModelBund/queryById"
        }
      }
    },
    computed: {
      formDisabled() {
        return this.disabled
      },
    },
    watch: {
      // 监听某个值的变化，根据其值来动态设置是否必填
      'model.spaceOne'(val) {
        //sss   debugger;
        this.urlIsrequired = val === "0" // 根据某个值来判断是否必填
        console.log(this.urlIsrequired)
      }
    },
    created() {
      //备份model原始值
      this.modelDefault = JSON.parse(JSON.stringify(this.model));
    },
    methods: {
      add() {
        this.edit(this.modelDefault);
      },
      edit(record) {
        this.model = Object.assign({}, record);
        this.visible = true;
      },
      submitForm() {
        const that = this;
        // 触发表单验证
        this.$refs.form.validate(valid => {
          if (valid) {
            that.confirmLoading = true;
            let httpurl = '';
            let method = '';
            if (!this.model.id) {
              httpurl += this.url.add;
              method = 'post';
            } else {
              httpurl += this.url.edit;
              method = 'put';
            }
            httpAction(httpurl, this.model, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
            }).finally(() => {
              that.confirmLoading = false;
            })
          }

        })
      },
      identyfyStatus(value) {

        if (value == 1) {
          this.urlIsrequired = true;



        } else {
          this.urlIsrequired = false;
        }

      }
    }
  }
</script>
