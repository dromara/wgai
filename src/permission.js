import Vue from 'vue'
import router from './router'
import store from './store'
import NProgress from 'nprogress' // progress bar
import 'nprogress/nprogress.css' // progress bar style
import notification from 'ant-design-vue/es/notification'
import { ACCESS_TOKEN, INDEX_MAIN_PAGE_PATH, OAUTH2_LOGIN_PAGE_PATH } from '@/store/mutation-types'
import { generateIndexRouter, isOAuth2AppEnv } from '@/utils/util'

NProgress.configure({ showSpinner: false }) // NProgress Configuration

const whiteList = ['/user/login', '/user/register', '/user/register-result', '/user/alteration'] // no redirect whitelist
whiteList.push(OAUTH2_LOGIN_PAGE_PATH)

router.beforeEach((to, from, next) => {
  // 解决三级菜单无法缓存问题
  if (to.matched && to.matched.length > 3) {
    to.matched.splice(2, to.matched.length - 3)
  }

  NProgress.start() // start progress bar

  if (Vue.ls.get(ACCESS_TOKEN)) {
    /* has token */
    if (to.path === '/user/login' || to.path === OAUTH2_LOGIN_PAGE_PATH) {
      console.log("INDEX_MAIN_PAGE_PATH", INDEX_MAIN_PAGE_PATH)
      next({ path: INDEX_MAIN_PAGE_PATH })
      NProgress.done()
    } else {
      // 判断是否已加载权限路由
      if (store.getters.permissionList.length === 0) {
        // 请求权限列表
        store.dispatch('GetPermissionList')
          .then(res => {
            const menuData = res.result.menu;
            
            if (!menuData || menuData.length === 0) {
              notification.error({
                message: '系统提示',
                description: '暂无菜单权限，请联系管理员！'
              })
              store.dispatch('Logout').then(() => {
                next({ path: '/user/login' })
              })
              return;
            }

            // 生成路由
            const constRoutes = generateIndexRouter(menuData);
            
            // 添加主界面路由
            store.dispatch('UpdateAppRouter', { constRoutes }).then(() => {
              // 动态添加可访问路由表
              router.addRoutes(store.getters.addRouters)
              
              // 关键修改：使用 next({ ...to, replace: true }) 重新进入路由
              // 确保 addRoutes 已完成
              next({ ...to, replace: true })
            })
          })
          .catch(() => {
            notification.error({
              message: '系统提示',
              description: '请求用户信息失败，请重试！'
            })
            store.dispatch('Logout').then(() => {
              next({ path: '/user/login', query: { redirect: to.fullPath } })
            })
          })
      } else {
        // 已有权限，直接放行
        next()
      }
    }
  } else {
    // 无 token
    if (whiteList.indexOf(to.path) !== -1) {
      // 在免登录白名单，如果进入的页面是login页面并且当前是OAuth2app环境，就进入OAuth2登录页面
      if (to.path === '/user/login' && isOAuth2AppEnv()) {
        next({ path: OAUTH2_LOGIN_PAGE_PATH })
      } else {
        // 在免登录白名单，直接进入
        next()
      }
      NProgress.done()
    } else {
      // 如果当前是在OAuth2APP环境，就跳转到OAuth2登录页面
      let path = isOAuth2AppEnv() ? OAUTH2_LOGIN_PAGE_PATH : '/user/login'
      next({ path: path, query: { redirect: to.fullPath } })
      NProgress.done() // if current page is login will not trigger afterEach hook, so manually handle it
    }
  }
})

router.afterEach(() => {
  NProgress.done() // finish progress bar
})