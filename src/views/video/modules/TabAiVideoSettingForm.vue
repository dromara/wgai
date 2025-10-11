<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="24">
            <a-form-model-item label="订阅ID" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="subId">
         <!--     <a-input v-model="model.subId" placeholder="请输入订阅ID"  ></a-input> -->
             <j-dict-select-tag type="list" v-model="model.subId" dictCode="tab_ai_subscription_new,name,id" placeholder="请选择订阅ID" />
             
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="识别方式" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="difyType">
            <j-dict-select-tag type="list" v-model="model.difyType" dictCode="dify_type"
              placeholder="识别方式" />
            </a-form-model-item>
          </a-col>
          <a-col :span="24">
            <a-form-model-item label="是否需要前置" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBefor">
            <j-dict-select-tag type="list" v-model="model.isBefor" dictCode="push_static"
              placeholder="是否需要前置" />
            </a-form-model-item>
          </a-col>
          
         
          
          <a-col :span="24"  v-if="model.isBefor==0">
            <a-form-model-item label="前置模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
              
           <j-search-select-tag  v-model="model.modelId" dict="tab_ai_model,ai_name,id" placeholder="请选择前置模型" />
           
            </a-form-model-item>
          </a-col>
          <a-col :span="24"  v-if="model.isBefor==0">
            <a-form-model-item label="前置识别内容" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelTxt">
                   
              
              <j-search-select-tag  v-model="model.modelTxt" dict="tab_ai_base,chain_name,chain_name" placeholder="前置识别内容" />
              
            </a-form-model-item>
          </a-col>
           <a-col :span="24"  v-if="model.isBefor==0">
                <div style="color: red;text-align: center;">跟随坐标,后置模型会在前置基础上识别内容，后置识别的坐标会在前置模型识别到的坐标加最大距离</div>
            </a-col>
            
          <a-col :span="24"   v-if="model.isBefor==0">
            <a-form-model-item label="是否跟随前置坐标" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isFollow">
            <j-dict-select-tag type="list" v-model="model.isFollow" dictCode="push_static"
              placeholder="是否跟随前置坐标" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24"   v-if="model.isFollow==0" >
            <a-form-model-item label="跟随最大距离" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="followPosition">
              <a-input-number  v-model="model.followPosition" placeholder="跟随最大距离"  style="width: 100%"  ></a-input-number>
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24"  v-if="model.isBefor==0">
               <div style="color: red;text-align: center;">跟随前置放大再识别，针对小物品识别，需要开启跟随前置坐标</div>
           </a-col>
          <a-col :span="24"  v-if="model.isBefor==0">
            <a-form-model-item label="是否跟随前置放大" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBeforZoom">
                   
              
           <j-dict-select-tag type="list" v-model="model.isBeforZoom" dictCode="push_static"
             placeholder="是否跟随前置放大" />
            </a-form-model-item>
          </a-col>
       
          <a-col :span="24">
            <a-form-model-item label="后置模型" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="nextMode">

   
              <j-search-select-tag  v-model="model.nextMode" dict="tab_ai_model,ai_name,id" placeholder="请选择前置模型" />
              
            </a-form-model-item>
          </a-col>
        
          <a-col :span="24"  >
            <a-form-model-item label="是否识别报警" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="warinngMethod">
            <j-dict-select-tag type="list" v-model="model.warinngMethod" dictCode="push_static"
              placeholder="是否识别报警" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24"  >
            <a-form-model-item label="是否开启区域识别" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="isBy">
            <j-dict-select-tag type="list" v-model="model.isBy" dictCode="push_static"
              placeholder="是否开启区域识别" />
            </a-form-model-item>
          </a-col>
           <a-col :span="24" v-if="model.warinngMethod==1" >
               <div style="color: red;text-align: center;">默认都是识别到报警，例外情况可以未识别到报警，需要填写未识别报警内容</div>
           </a-col>
          <a-col :span="24"  v-if="model.warinngMethod==1">
            <a-form-model-item label="未识别到预警文本" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="noDifText">
    
              <j-search-select-tag  v-model="model.noDifText" dict="tab_ai_base,chain_name,chain_name" placeholder="未识别到预警文本" />
      
            </a-form-model-item>
          </a-col>
          
          <a-col :span="24">
            <a-form-model-item label="备用1" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="spareOne">
              <a-input v-model="model.spareOne" placeholder="请输入备用1"  ></a-input>
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
    name: 'TabAiVideoSettingForm',
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
          followPosition:0,
          warinngMethod:0,
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
         subId:[
             { required: true, message: '请选择视频!'},
          ],
         isBefor:[
             { required: true, message: '请选择是否需要前置!'},
          ],
          nextMode:[
              { required: true, message: '请选择是否需要前置!'},
           ]
          
        },
        url: {
          add: "/video/tabAiVideoSetting/add",
          edit: "/video/tabAiVideoSetting/edit",
          queryById: "/video/tabAiVideoSetting/queryById"
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