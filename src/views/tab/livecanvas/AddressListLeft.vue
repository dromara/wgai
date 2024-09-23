<template>
  <a-card :loading="cardLoading" :bordered="false" style="height: 100%;">
    <a-spin :spinning="loading">
      <a-input-search @search="handleSearch" style="width:100%;margin-top: 10px" placeholder="输入模型名称查询..."
        enterButton />

      <a-tree showLine checkStrictly :expandedKeys.sync="expandedKeys" :selectedKeys="selectedKeys"
        :dropdownStyle="{maxHeight:'200px',overflow:'auto'}" :treeData="treeDataSource" @select="handleTreeSelect" />
    </a-spin>
  </a-card>
</template>

<script>
  // import {
  //   queryDepartTreeList,
  //   searchByKeywords
  // } from '@/api/api'

  import {
    httpAction,
    getAction
  } from '@/api/manage'
  import {
    Tree
  } from 'ant-design-vue'
  export default {
    name: 'AddressListLeft',
    props: ['value'],
    data() {
      return {
        cardLoading: true,
        loading: false,
        treeDataSource: [],
        selectedKeys: [],
        expandedKeys: []
      }
    },
    created() {
      this.queryTreeData()
    },
    methods: {

      queryTreeData(keyword) {
        this.queryDepartTreeList({
          departName: keyword ? keyword : undefined
        })
      },
      queryDepartTreeList(modelName) {

        this.commonRequestThen(getAction("/tab/tabAiModelBund/Treelist", modelName))
      },

      handleSearch(value) {
        if (value) {
          this.commonRequestThen(getAction("/tab/tabAiModelBund/Treelist", {
            modelName: value
          }))
        } else {
          this.queryTreeData()
        }
      },

      handleTreeSelect(selectedKeys, event) {
        //update-begin---author:wangshuai ---date:20220107  for：[JTC-378]通讯录 选中某个部门查询部门人员，想再取消选中查全部，无法取消，只能重新刷新界面------------
     //   debugger;
        if (selectedKeys.length > 0) {
          if (this.selectedKeys[0] !== selectedKeys[0]) {
            this.selectedKeys = [selectedKeys[0]];
          
            let data= event.node.dataRef.id;
            let url = event.node.dataRef.url
            this.emitInput(url+";"+data)
          }
        } else {
          this.selectedKeys = []
          this.emitInput("")
        }
        //update-end---author:wangshuai ---date:20220107  for：[JTC-378]通讯录 选中某个部门查询部门人员，想再取消选中查全部，无法取消，只能重新刷新界面------------
      },

      emitInput(url) {
        this.$emit('input', url)
      },

      commonRequestThen(promise) {
        this.loading = true
        promise.then(res => {
          if (res.success) {
            console.log(res.result);
            let list = res.result

            this.treeDataSource = [];
            for (var i = 0; i < list.length; i++) {
              let treeData = {};
              treeData.id = list[i].id;
              treeData.title = list[i].spaceTwo;
              treeData.name = list[i].spaceTwo;
              treeData.key = list[i].id;
              treeData.icon = "home|svg"
              treeData.url = list[i].sendUrl;
              treeData.children = [];
              this.treeDataSource.push(treeData);
            }

            // update-begin- --- author:wangshuai ------ date:20200102 ---- for:去除默认选中第一条数据、默认展开所有第一级
            // 默认选中第一条数据、默认展开所有第一级
            // if (res.result.length > 0) {
            //   this.expandedKeys = []
            //   res.result.forEach((item, index) => {
            //     if (index === 0) {
            //       this.selectedKeys = [item.id]
            //       this.emitInput(item.orgCode)
            //     }
            //     this.expandedKeys.push(item.id)
            //   })
            // }
            // update-end- --- author:wangshuai ------ date:20200102 ---- for:去除默认选中第一条数据、默认展开所有第一级
          } else {
            this.$message.warn(res.message)
            console.error('组织机构查询失败:', res)
          }
        }).finally(() => {
          this.loading = false
          this.cardLoading = false
        })
      },

    }
  }
</script>

<style scoped>

</style>
