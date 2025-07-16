<template>
  <div class="logo" style="background-color: #fff!important;">
    <router-link :to="routerLinkTo">

      <!-- update-begin- author:sunjianlei --- date:20190814 --- for: logo颜色根据主题颜色变化 -->
      <!-- <img v-if="navTheme === 'dark'" src="~@/assets/logo-white.png" alt="logo"> -->
      <img src="~@/assets/zwyStyle/img/logo2.png" alt="logo">
      <!-- update-begin- author:sunjianlei --- date:20190814 --- for: logo颜色根据主题颜色变化 -->
      <h1 v-if="showTitle" style="color: #0364ff;font-size: 24px;">{{ title }}</h1>
    </router-link>
  </div>
</template>

<script>
  import { mixin } from '@/utils/mixin.js'

  export default {
    name: 'Logo',
    mixins: [mixin],
    props: {
      title: {
        type: String,
        default: 'AI赋能训练',
        required: false
      },
      showTitle: {
        type: Boolean,
        default: true,
        required: false
      },
      // 点击Logo跳转地址
      routerLinkTo: {
        type: Object,
        default: () => ({name: 'dashboard'}),
      },
    }
  }
</script>
<style lang="less" scoped>
  /*缩小首页布 局顶部的高度*/
  @height: 70px;

  .sider {
    box-shadow: none !important;
    .logo {
      height: @height !important;
      line-height: @height !important;
      box-shadow: none !important;
      transition: background 300ms;

      a {
        // color: white;
        // &:hover {
        //   color: rgba(255, 255, 255, 0.8);
        // }
      }
    }

    // &.light .logo {
    //   background-color: @primary-color;
    // }
  }
</style>