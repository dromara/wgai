<template>
  <j-modal

    :title="title"
    :width="width"
    
    :visible="visible"
    switchFullscreen
    :footer="null"
   :fullscreen="true"
    @cancel="handleCancel"
>
    <address-list-right-pic    ref="realForm" @ok="submitCallback" :disabled="disableSubmit"></address-list-right-pic> 
  </j-modal>
</template>

<script>


 import AddressListRightPic from '../../tab/livecanvas/AddressListRightPicSetting'
  export default {
    //name: 'TabVideoUtilModal',
    components: {

      AddressListRightPic
    },
    data () {
      return {
        title:'配置识别区域范围',
        width:'90%',
        visible: false,
        disableSubmit: false
      }
    },
    methods: {
      add () {
        this.visible=true
        this.$nextTick(()=>{
          this.$refs.realForm.add();
        })
      },
      edit (record) {
        this.visible=true
        console.log("record",record)
        this.$nextTick(()=>{
          this.$refs.realForm.edit(record);
        })
      },
      close () {
        this.$emit('close');
        this.visible = false;
      },
      handleOk () {
        this.$refs.realForm.submitForm();
      },
      submitCallback(){
        this.$emit('ok');
        this.visible = false;
      },
      handleCancel () {
        this.close()
      },
      
    }
  }
</script>