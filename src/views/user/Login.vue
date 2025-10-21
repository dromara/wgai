<template>
  <div class="main">
    <a-form-model class="user-layout-login" >
      <a-tabs :activeKey="customActiveKey" :tabBarStyle="{ textAlign: 'center', borderBottom: 'unset' }"  @change="handleTabClick">
        <a-tab-pane key="tab1" tab="">
          <login-account ref="alogin" @validateFail="validateFail" @success="requestSuccess" @fail="requestFailed"></login-account>
        </a-tab-pane>

      <!--  <a-tab-pane key="tab2" tab="手机号登录">
          <login-phone ref="plogin" @validateFail="validateFail" @success="requestSuccess" @fail="requestFailed"></login-phone>
        </a-tab-pane> -->
      </a-tabs>

  <!--    <a-form-model-item>
        <a-checkbox @change="handleRememberMeChange" default-checked>自动登录</a-checkbox>
        <router-link :to="{ name: 'alteration'}" class="forge-password" style="float: right;">
          忘记密码
        </router-link>
        <router-link :to="{ name: 'register'}" class="forge-password" style="float: right;margin-right: 10px" >
          注册账户
        </router-link>
      </a-form-model-item> -->

      <a-form-item style="margin-top:24px">
     <!--  <a-button size="large"  type="primary"  htmlType="submit"  class="login-button"  :loading="loginBtn"  @click.stop.prevent="handleSubmitStar" :disabled="loginBtn">使用 Gitee 账号 Star
        </a-button> -->
        <a-button size="large"  type="primary"  htmlType="submit"  class="login-button"  :loading="loginBtn"  @click.stop.prevent="handleSubmit" :disabled="loginBtn">登录
        </a-button>
      </a-form-item>

    </a-form-model>

    <two-step-captcha v-if="requiredTwoStepCaptcha" :visible="stepCaptchaVisible" @success="stepCaptchaSuccess" @cancel="stepCaptchaCancel"></two-step-captcha>
    <login-select-tenant ref="loginSelect" @success="loginSelectOk"></login-select-tenant>
    <third-login ref="thirdLogin"></third-login>
  </div>
</template>

<script>
import Vue from 'vue'
import { ACCESS_TOKEN, ENCRYPTED_STRING } from '@/store/mutation-types'
import ThirdLogin from './third/ThirdLogin'
import LoginSelectTenant from './LoginSelectTenant'
import TwoStepCaptcha from '@/components/tools/TwoStepCaptcha'
import { getEncryptedString } from '@/utils/encryption/aesEncrypt'
import { timeFix } from '@/utils/util'
import Cookies from "js-cookie";
import LoginAccount from './LoginAccount'
import LoginPhone from './LoginPhone'

export default {
    components: {
      LoginSelectTenant,
      TwoStepCaptcha,
      ThirdLogin,
      LoginAccount,
      LoginPhone
    },
    data () {
      return {
        customActiveKey: 'tab1',
        rememberMe: true,
        loginBtn: false,
        requiredTwoStepCaptcha: false,
        stepCaptchaVisible: false,
        encryptedString:{
          key:"",
          iv:"",
        },
      }
    },
    created() {
      Vue.ls.remove(ACCESS_TOKEN)
      this.getRouterData();
      this.rememberMe = true;
      const giteeStarId = this.$route.query.giteeStarId;
          if (giteeStarId) {
            Cookies.set("giteeStarId", giteeStarId, { expires: 24 * 60 });
            this.$modal.msgSuccess("已授权");
          }
          this.getCode();
          this.getCookie();
    },
    methods:{
       getCode() {
            getCodeImg().then(res => {
              this.captchaEnabled = res.captchaEnabled === undefined ? true : res.captchaEnabled;
              if (this.captchaEnabled) {
                this.codeUrl = "data:image/gif;base64," + res.img;
                this.loginForm.uuid = res.uuid;
              }
            });
          },
          getCookie() {
            const username = Cookies.get("username");
            const password = Cookies.get("password");
            const rememberMe = Cookies.get('rememberMe')
            this.loginForm = {
              username: username === undefined ? this.loginForm.username : username,
              password: password === undefined ? this.loginForm.password : decrypt(password),
              rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
            };
          },
      handleTabClick(key){
        this.customActiveKey = key
      },
      handleRememberMeChange(e){
        this.rememberMe = e.target.checked
      },
      /**跳转到登录页面的参数-账号获取*/
      getRouterData(){
        this.$nextTick(() => {
          let temp = this.$route.params.username || this.$route.query.username || ''
          if (temp) {
            this.$refs.alogin.acceptUsername(temp)
          }
        })
      },
      handleSubmitStar(){
          window.location.href = `https://gitee.com/dromara/wgai.git`;
      },
      //登录
      handleSubmit () {
        this.loginBtn = true;
        if (this.customActiveKey === 'tab1') {
          // 使用账户密码登录
             let giteeStarId = Cookies.get("giteeStarId");
             console.log(giteeStarId)
          this.$refs.alogin.handleLogin(this.rememberMe)
        } else {
          //手机号码登录
          this.$refs.plogin.handleLogin(this.rememberMe)
        }
      },
      // 校验失败
      validateFail(){
        this.loginBtn = false;
      },
      // 登录后台成功
      requestSuccess(loginResult){
        this.$refs.loginSelect.show(loginResult)
      },
      //登录后台失败
      requestFailed (err) {
        let description = ((err.response || {}).data || {}).message || err.message || "请求出现错误，请稍后再试"
        this.$notification[ 'error' ]({
          message: '登录失败',
          description: description,
          duration: 4,
        });
        //账户密码登录错误后更新验证码
        if(this.customActiveKey === 'tab1' && description.indexOf('密码错误')>0){
          this.$refs.alogin.handleChangeCheckCode()
        }
        this.loginBtn = false;
      },
      loginSelectOk(){
        this.loginSuccess()
      },
      //登录成功
      loginSuccess () {
        
      
        this.$notification.success({
          message: '欢迎',
          description: `${timeFix()}，欢迎回来`,
        });
          console.log("xxxxxx")
          // 登录后，主动加载权限
         this.$router.replace({ path: '/dashboard/analysis' }).catch(()=>{});
          // this.$store.dispatch('GetPermissionList')
          //   .then(() => {
          //      console.log("yyy")
          //     // 确保动态路由已经 addRoutes 完成后再跳转
          //     this.$router.replace({ path: '/dashboard/analysis' }).catch((err)=>{
          //          console.error('获取权限失败：', err);
          //       });
          //   })
          //   .catch(err => {
          //     console.error('获取权限失败：', err);
          //     this.$router.replace({ path: '/user/login' }).catch(()=>{});
          //   });
         // 如果你在 Login 后没有立即请求权限，这里主动去请求并等待结果
          // this.$store.dispatch('GetPermissionList').then(res => {
          //   // 成功拿到权限并触发了 UpdateAppRouter -> router.addRoutes
          //   // 这里使用 replace 防止多余历史记录
          //   this.$router.replace({ path: '/dashboard/analysis' }).catch(()=>{});
          // }).catch(err => {
          //   console.error('获取权限失败：', err);
          //   // 兜底跳转到登录页或提示
          //   this.$router.replace({ path: '/user/login' }).catch(()=>{});
          // });
     
      },

      stepCaptchaSuccess () {
        this.loginSuccess()
      },
      stepCaptchaCancel () {
        this.Logout().then(() => {
          this.loginBtn = false
          this.stepCaptchaVisible = false
        })
      },
      //获取密码加密规则
      getEncrypte(){
        var encryptedString = Vue.ls.get(ENCRYPTED_STRING);
        if(encryptedString == null){
          getEncryptedString().then((data) => {
            this.encryptedString = data
          });
        }else{
          this.encryptedString = encryptedString;
        }
      }

    }

  }
</script>
<style lang="less" scoped>
  .user-layout-login {
    label {
      font-size: 14px;
    }
  .getCaptcha {
      display: block;
      width: 100%;
      height: 40px;
    }

  .forge-password {
      font-size: 14px;
    }

    button.login-button {
      padding: 0 15px;
      font-size: 16px;
      height: 40px;
      width: 100%;
    }

  .user-login-other {
      text-align: left;
      margin-top: 24px;
      line-height: 22px;

    .item-icon {
        font-size: 24px;
        color: rgba(0,0,0,.2);
        margin-left: 16px;
        vertical-align: middle;
        cursor: pointer;
        transition: color .3s;

      &:hover {
          color: #0364ff;
        }
      }

    .register {
        float: right;
      }
    }
  }
</style>
<style>
  .valid-error .ant-select-selection__placeholder{
    color: #f5222d;
  }
</style>