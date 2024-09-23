<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="原始视频参数" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoStart">
              <a-input v-model="model.videoStart" placeholder="请输入原始尺寸"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="原始X坐标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoStartx">
              <a-input v-model="model.videoStartx" placeholder="请输入原始X坐标"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="原始y坐标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoStarty">
              <a-input v-model="model.videoStarty" placeholder="请输入原始y坐标"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="结束坐标x" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoEndx">
              <a-input v-model="model.videoEndx" placeholder="请输入结束坐标x"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="结束坐标y" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoEndy">
              <a-input v-model="model.videoEndy" placeholder="请输入结束坐标y"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="入侵内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoJson">
              <a-input v-model="model.videoJson" placeholder="请输入其他内容"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际尺寸" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasStart">
              <a-input v-model="model.canvasStart" placeholder="请输入实际尺寸"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际X坐标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasStartx">
              <a-input v-model="model.canvasStartx" placeholder="请输入实际X坐标"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际y坐标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasStarty">
              <a-input v-model="model.canvasStarty" placeholder="请输入实际y坐标"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际宽度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasWidth">
              <a-input v-model="model.canvasWidth" placeholder="请输入实际宽度"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际高度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasHeight">
              <a-input v-model="model.canvasHeight" placeholder="请输入实际高度"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="实际入侵视频参数" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="canvasJson">
              <a-input v-model="model.canvasJson" placeholder="请输入其他内容"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="remerk" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remerk">
              <a-input v-model="model.remerk" placeholder="请输入remerk"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spareOne">
              <a-input v-model="model.spareOne" placeholder="请输入备注"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="视频id" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoId">
              <a-input v-model="model.videoId" placeholder="请输入视频id"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="视频名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoName">
              <a-input v-model="model.videoName" placeholder="请输入视频名称"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备注2" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spareTwo">
              <a-input v-model="model.spareTwo" placeholder="请输入备注2"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备注3" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spateThree">
              <a-input v-model="model.spateThree" placeholder="请输入备注3"  ></a-input>
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
    name: 'TabVideoUtilForm',
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
          add: "/video/tabVideoUtil/add",
          edit: "/video/tabVideoUtil/edit",
          queryById: "/video/tabVideoUtil/queryById"
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