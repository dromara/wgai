<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <!-- <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
        </a-row>
      </a-form>
    </div> -->
    <!-- 查询区域-END -->

 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" class="xz" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('数字人')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" class="dr" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
        </a-menu>
        <a-button style="margin-left: 8px"> 批量操作 <a-icon type="down" /></a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div class="datagrid-view">
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <a-table
        ref="table"
        size="middle"
        :scroll="{x:true}"
        bordered
        rowKey="id"
        :columns="columns"
        :dataSource="dataSource"
        :pagination="ipagination"
        :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        class="j-table-force-nowrap"
        @change="handleTableChange">

        <template slot="htmlSlot" slot-scope="text">
          <div v-html="text"></div>
        </template>
        <template slot="imgSlot" slot-scope="text,record">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
          <img v-else :src="getImgView(text)" :preview="record.id" height="25px" alt="" style="max-width:80px;font-size: 12px;font-style: italic;"/>
        </template>
        <template slot="fileSlot" slot-scope="text">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
          <a-button
            v-else
            :ghost="true"
            type="primary"
            icon="download"
            size="small"
            @click="downloadFile(text)">
            下载
          </a-button>
        </template>

        <span slot="action" slot-scope="text, record">
          <a @click="handleEdit(record)">编辑</a>

          <a-divider type="vertical" />
          <a @click="startTrain(record)">开始训练</a>
          
          <a-divider type="vertical" />
          <a-dropdown>
            <a class="ant-dropdown-link">更多 <a-icon type="down" /></a>
            <a-menu slot="overlay">
              <a-menu-item>
                <a @click="handleDetail(record)">详情</a>
              </a-menu-item>
              <a-menu-item>
                <a-popconfirm title="确定删除吗?" @confirm="() => handleDelete(record.id)">
                  <a>删除</a>
                </a-popconfirm>
              </a-menu-item>
            </a-menu>
          </a-dropdown>
        </span>

      </a-table>
    </div>

    <tab-szr-video-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-szr-video-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabSzrVideoModal from './modules/TabSzrVideoModal'
  import {
    httpAction,
    getAction,
    uploadAction 
  } from '@/api/manage'
  export default {
    name: 'TabSzrVideoList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabSzrVideoModal
    },
    data () {
      return {
        description: '数字人管理页面',
        // 表头
        columns: [
          {
            title: '#',
            dataIndex: '',
            key:'rowIndex',
            width:60,
            align:"center",
            customRender:function (t,r,index) {
              return parseInt(index)+1;
            }
          },
          {
            title:'数字人名称',
            align:"center",
            dataIndex: 'szrName'
          },
          {
            title:'数字人图片',
            align:"center",
            dataIndex: 'szrIcon',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'数字人视频',
            align:"center",
            dataIndex: 'szrVideo',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'数字人简介',
            align:"center",
            dataIndex: 'szrText'
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'szrOther'
          },
          {
            title:'数字人目录',
            align:"center",
            dataIndex: 'szrPath'
          },
          {
            title:'训练状态',
            align:"center",
            dataIndex: 'starFlag',
            customRender:function (t,r,index) {
              console.log("t,r",t,r)
              if(t==null||t==0){
                return '未在训练'
              }
              return '训练中'
            }
          },
          {
            title:'是否可用',
            align:"center",
            dataIndex: 'isOk',
            customRender:function (t,r,index) {
              console.log("t,r",t,r)
              if(t==null||t==0){
                return '不可用'
              }
              return '可用'
            }
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:147,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/szr/tabSzrVideo/list",
          delete: "/szr/tabSzrVideo/delete",
          deleteBatch: "/szr/tabSzrVideo/deleteBatch",
          exportXlsUrl: "/szr/tabSzrVideo/exportXls",
          importExcelUrl: "szr/tabSzrVideo/importExcel",
          
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
    this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function(){
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'szrName',text:'数字人名称',dictCode:''})
        fieldList.push({type:'string',value:'szrIcon',text:'数字人图片',dictCode:''})
        fieldList.push({type:'string',value:'szrVideo',text:'数字人视频',dictCode:''})
        fieldList.push({type:'string',value:'szrText',text:'数字人简介',dictCode:''})
        fieldList.push({type:'string',value:'szrOther',text:'备注',dictCode:''})
        fieldList.push({type:'string',value:'szrPath',text:'数字人目录',dictCode:''})
        fieldList.push({type:'int',value:'starFlag',text:'训练状态',dictCode:''})
        fieldList.push({type:'int',value:'isOk',text:'是否可用',dictCode:''})
        this.superFieldList = fieldList
      },
      startTrain(record){
        let that=this;
        getAction("/szr/tabSzrPython/startPy", {id:record.id}).then((res) => {
          if (res.success) {
        
             that.$message.success("开始训练");
          
          } else {
           thast.$message.warning("训练失败");
          }
        })
      },
      startTest(record){
        let that=this;
        getAction("/szr/tabSzrPython/testStar", {id:record.id}).then((res) => {
          if (res.success) {
        
             that.$message.success("开始训练");
          
          } else {
           thast.$message.warning("训练失败");
          }
        })
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(74vh - 91px) !important;overflow: auto;
}
</style>