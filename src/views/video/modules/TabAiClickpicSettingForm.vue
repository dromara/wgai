<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="所属模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
              <j-search-select-tag v-model="model.modelId" dict="tab_model_try,model_name,id"  />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="视频类型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoType">
              <j-dict-select-tag type="list" @change="changeVideoType" v-model="model.videoType" dictCode="video_type" placeholder="请选择视频类型" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="视频地址" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="videoUrl">
              
             <template v-if="model.videoType == 1">
                  <j-upload v-model="model.videoUrl"></j-upload>
                </template>
            
                <!-- 否则显示 a-input -->
                <template v-else>
                  <a-input v-model="model.videoUrl" placeholder="视频流地址"></a-input>
                </template>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="间隔帧" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="interFrameInterval">
              <a-input-number v-model="model.interFrameInterval" placeholder="请输入间隔帧" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="采集数量" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="picNumber">
              <a-input-number v-model="model.picNumber" placeholder="请输入采集数量" style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="保存目录(不勾选图片模型库生效)" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="savePath">
              <a-input v-model="model.savePath" placeholder="请输入保存目录(不勾选图片模型库生效)"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="备注" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="remake">
              <a-input v-model="model.remake" placeholder="请输入备注"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否覆盖" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isCover">
              <j-switch v-model="model.isCover"  ></j-switch>
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否放入图片模型库" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="picModelInster">
              <j-switch v-model="model.picModelInster"  ></j-switch>
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
    name: 'TabAiClickpicSettingForm',
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
          sm: { span: 8 },
        },
        wrapperCol: {
          xs: { span: 24 },
          sm: { span: 13 },
        },
        confirmLoading: false,
        validatorRules: {
           videoUrl: [
              { required: true, message: '请输入视频内容!'},
           ],
           modelId: [
              { required: true, message: '请输入所属模型!'},
           ],
           videoType: [
              { required: true, message: '请输入视频类型!'},
           ],
           interFrameInterval: [
              { required: true, message: '请输入间隔帧!'},
           ],
           picNumber: [
              { required: true, message: '请输入采集数量!'},
           ],
           savePath: [
              { required: true, message: '请输入保存目录(不勾选图片模型库生效)!'},
           ],
        },
        url: {
          add: "/video/tabAiClickpicSetting/add",
          edit: "/video/tabAiClickpicSetting/edit",
          queryById: "/video/tabAiClickpicSetting/queryById"
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
      changeVideoType(any){
        console.log(any)
        if(any==0){//选择视频流
          console.log("当前视频流",any)
        }else{
           console.log("当前视频流2",any)
        }
      },
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