<template>
  <j-modal
    :title="title"
    :width="width"
    :visible="visible"
    switchFullscreen
    @ok="handleOk"
    @cancel="handleCancel"
    cancelText="关闭">
    <tab-face-pic-batch-form ref="realForm" @ok="submitCallback"></tab-face-pic-batch-form>
  </j-modal>
</template>

<script>
  import TabFacePicBatchForm from './TabFacePicBatchForm'
  
  export default {
    name: 'TabFacePicBatchModal',
    components: {
      TabFacePicBatchForm
    },
    data () {
      return {
        title: '批量添加人脸',
        width: 900,
        visible: false
      }
    },
    methods: {
      show () {
        this.visible = true
        this.$nextTick(() => {
          this.$refs.realForm.add();
        })
      },
      close () {
        this.$emit('close');
        this.visible = false;
      },
      handleOk () {
        this.$refs.realForm.submitForm();
      },
      submitCallback () {
        this.$emit('ok');
        this.visible = false;
      },
      handleCancel () {
        this.close()
      }
    }
  }
</script>