<template>
  <a-spin :spinning="confirmLoading">
    <j-form-container :disabled="formDisabled">
      <a-form-model ref="form" :model="model" :rules="validatorRules" slot="detail">
        <a-row>
          <a-col :span="6">
            <a-form-model-item label="训练开始时间" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="startTime">
              <j-date placeholder="请选择开始时间" v-model="model.startTime"  style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练结束时间" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="endTime">
              <j-date placeholder="请选择结束时间" v-model="model.endTime"  style="width: 100%" />
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练状态" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainState">
              <a-input v-model="model.trainState" placeholder="请输入训练状态"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="评估类别" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainClass">
              <a-input v-model="model.trainClass" placeholder="请输入评估类别"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="测试图片数量" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainImages">
              <a-input v-model="model.trainImages" placeholder="请输入测试图片数量"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="精度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="percision">
              <a-input v-model="model.percision" placeholder="请输入精度"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="召回率" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="recall">
              <a-input v-model="model.recall" placeholder="请输入召回率"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="平均精度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="map50">
              <a-input v-model="model.map50" placeholder="请输入平均精度"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="0.5_0.9下的平均精度" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="map5095">
              <a-input v-model="model.map5095" placeholder="请输入0.5_0.9下的平均精度"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练次数" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="epochs">
              <a-input v-model="model.epochs" placeholder="训练次数"  ></a-input>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="标签图片" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="labels">
              <j-image-upload isMultiple  v-model="model.labels" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="标签相关图" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="labelsCorrelogram">
              <j-image-upload isMultiple  v-model="model.labelsCorrelogram" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练批次0" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainBatch0">
              <j-image-upload isMultiple  v-model="model.trainBatch0" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练批次1" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainBatch1">
              <j-image-upload isMultiple  v-model="model.trainBatch1" ></j-image-upload>
            </a-form-model-item>
          </a-col>
     
          <a-col :span="6">
            <a-form-model-item label="训练批次2" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainBatch2">
              <j-image-upload isMultiple  v-model="model.trainBatch2" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练结果验证0" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainBatch0">
              <j-image-upload isMultiple  v-model="model.valBatch0Lables" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="训练结果验证1" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="trainBatch1">
              <j-image-upload isMultiple  v-model="model.valBatch0Pred" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="PR曲线" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="prCurve">
              <j-image-upload isMultiple  v-model="model.prCurve" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="混淆矩阵" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="confusionMatrix">
              <j-image-upload isMultiple  v-model="model.confusionMatrix" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="F1曲线" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="f1Curve">
              <j-image-upload isMultiple  v-model="model.f1Curve" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="P曲线" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="ppCurve">
              <j-image-upload isMultiple  v-model="model.ppCurve" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="R曲线" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="rrCurve">
              <j-image-upload isMultiple  v-model="model.rrCurve" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="结果曲线" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="results">
              <j-image-upload isMultiple  v-model="model.results" ></j-image-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="hyp文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="hypYaml">
              <j-upload v-model="model.hypYaml"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="opt文件" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="optYaml">
              <j-upload v-model="model.optYaml"   ></j-upload>
            </a-form-model-item>
          </a-col>
          <a-col :span="6">
            <a-form-model-item label="模型名称" :labelCol="labelCol" :wrapperCol="wrapperCol" prop="modelId">
              <j-dict-select-tag type="list" v-model="model.modelId" dictCode="tab_model_try,model_name,id" placeholder="请选择模型名称" />
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
    name: 'TabTrainResultFormShow',
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
          xs: { span: 24},
          sm: { span: 24 },
        },
        wrapperCol: {
          xs: { span: 24 },
          sm: { span: 24 },
        },
        confirmLoading: false,
        validatorRules: {
        },
        url: {
          add: "/train/tabTrainResult/add",
          edit: "/train/tabTrainResult/edit",
          queryById: "/train/tabTrainResult/queryById"
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
        console.log("edit",record)
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
<style>
  .ant-form-item-label {
    text-align: left !important;
  }
  .ant-upload-list-picture-card .ant-upload-list-item{
      width:200px;
      height:150px;
  }
</style>