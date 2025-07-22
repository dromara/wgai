<template>
  <a-row class="videoList" type="flex" :gutter="16">
 
    <a-col :md="24" :sm="24">
      <test-video style="
    width: 25%;
    float: right;
" v-model="currentOrgCode" />
      <test-video1 style="
    width: 25%;
    float: right;
" v-model="currentOrgCode2" />
       <test-video2 style="
    width: 25%;
    float: right;
"  v-model="currentOrgCode3" />
  <test-video4 style="
    width: 25%;
    float: right;
"  v-model="currentOrgCode4" />
    </a-col>
 
   

    </a-col>
  </a-row>
</template>

<script>

  import testVideo from './testVideo'
  import testVideo1 from './testVideo1'
  import testVideo2 from './testVideo2'
   import testVideo4 from './testVideo4'
  export default {
    name: 'AddressList',
    components: { testVideo,testVideo1,testVideo2,testVideo4 },
    data() {
      return {
        description: 'AI视频分析',
        currentOrgCode: '',
        currentOrgCode2: '',
        currentOrgCode3: '',
          currentOrgCode4: ''
      }
    },

    methods: {}
  }
</script>
<style scoped>
  @import '~@assets/less/commondiy.less';
 .ant-card-body {
   padding: 0 !important;
 }
</style>
<style>
.videoList{padding: 10px;box-sizing: border-box;box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);border-radius: 10px;background: linear-gradient(to top, #ffffff, #f5faff) !important;height:calc(100vh - 173px);min-height: 740px;border-radius: 10px!important;overflow: hidden;width:calc(100% - 20px);margin: 10px!important;}
.videoList input{background: #eff3f8;border-radius:4px;border: 1px solid #d9d9d9;height: 32px;line-height: 32px;margin-right: 5px;}
.videoList .ant-card{background: none!important;}
.videoList button{height: 32px;margin-right: 5px;border-radius: 4px;color: #fff;cursor: pointer;}
.videoList button:nth-child(2){background: #4CAF50;border: 1px solid #4CAF50;}
.videoList button:nth-child(3){background: #ff9800;border: 1px solid #ff9800;}
</style>