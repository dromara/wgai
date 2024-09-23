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
    <address-list-right    ref="realForm" @ok="submitCallback" :disabled="disableSubmit"></address-list-right> 
  </j-modal>
</template>

<script>


 import AddressListRight from '../../tab/livecanvas/AddressListRight'
  export default {
    //name: 'TabVideoUtilModal',
    components: {

      AddressListRight
    },
    data () {
      return {
        title:'配置区域入侵',
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