<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
  <div class="table-page-search-wrapper">
    <a-form layout="inline" @keyup.enter.native="searchQuery">
      <a-row :gutter="24">
        <a-col :xl="6" :lg="7" :md="8" :sm="24">
          <a-form-item label="预警摄像头">
            <a-input placeholder="预警摄像头" v-model="queryParam.warningName"></a-input>
          </a-form-item>
        </a-col>
        <a-col :xl="6" :lg="7" :md="8" :sm="24">
          <a-form-item label="预警算法">
            <a-input placeholder="预警算法" v-model="queryParam.warningAi"></a-input>
          </a-form-item>
        </a-col>
        <a-col :xl="6" :lg="7" :md="8" :sm="24">
          <a-form-item label="预警算法">
            <a-input placeholder="预警算法" v-model="queryParam.warningAi"></a-input>
          </a-form-item>
        </a-col>
              <a-col :xl="10" :lg="10" :md="12" :sm="24">
                  <a-form-item label="预警时间">
                              <j-date showTime="true" dateFormat="YYYY-MM-DD HH:mm:ss" placeholder="请选择开始日期" class="query-group-cust" v-model="queryParam.warningTime_begin"></j-date>
                              <span class="query-group-split-cust"></span>
                              <j-date showTime="true" dateFormat="YYYY-MM-DD HH:mm:ss"  placeholder="请选择结束日期" class="query-group-cust" v-model="queryParam.warningTime_end"></j-date>
                  </a-form-item>
               </a-col>
                
        <a-col :xl="6" :lg="7" :md="8" :sm="24">
          <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
            <a-button type="primary" @click="searchQuery" class="cx" icon="search">查询</a-button>
            <a-button type="primary" @click="searchReset" class="cz" icon="reload" style="margin-left: 8px">重置</a-button>
            <a @click="handleToggleSearch" style="margin-left: 8px">
              {{ toggleSearchStatus ? '收起' : '展开' }}
              <a-icon :type="toggleSearchStatus ? 'up' : 'down'"/>
            </a>
          </span>
        </a-col>
        
      </a-row>
    </a-form>
  </div>
    <!-- 查询区域-END -->

    <div class="contable">
      <!-- 操作按钮区域 -->
      <div class="table-operator">
        <a-button @click="handleAdd" type="primary" class="xz" icon="plus">新增</a-button>
        <a-button type="primary" icon="download" class="dc" @click="handleExportXls('报警信息')">导出</a-button>
        <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl"
          @change="handleImportExcel">
          <a-button type="primary" class="dr" icon="import">导入</a-button>
        </a-upload>
        <!-- 高级查询区域 -->
        <a-dropdown v-if="selectedRowKeys.length > 0">
          <a-menu slot="overlay">
            
         <!--   <a-menu-item key="2" @click="batchTrain"><a-icon type="delete" />批量训练</a-menu-item> -->
            <a-menu-item key="1" @click="batchDel"><a-icon type="delete" />删除</a-menu-item>
            
     
     
          </a-menu>
          <a-button style="margin-left: 8px"> 批量操作 <a-icon type="down" /></a-button>
        </a-dropdown>
      </div>

      <!-- table区域-begin -->
      <div class="datagrid-view">
        <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
          <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a
            style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
          <a style="margin-left: 24px" @click="onClearSelected">清空</a>
        </div>

        <a-table ref="table" size="middle" :scroll="{x:true}" bordered rowKey="id" :columns="columns"
          :dataSource="dataSource" :pagination="ipagination" :loading="loading"
          :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}" class="j-table-force-nowrap"
          @change="handleTableChange">


          <!-- 图片预览 -->
          <template slot="warningPic" slot-scope="text">
            <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
            <img v-else :src="formatPath(text)" @click="openPreview(formatPath(text))" :preview="true"
              :style="{ display: 'inline-block', width: '120px', height: '120px', cursor: 'pointer' }">
            </img>
          </template>

          <!-- 视频预览 -->
          <template slot="warningCome" slot-scope="text">
              <span v-if="!text" style="font-size: 12px;font-style: italic;">无视频</span>
            <video v-if="text" :src="formatPath(text)" controls
              style="width: 120px; height: 120px; object-fit: cover"></video>
          </template>


          <template slot="htmlSlot" slot-scope="text">
            <div v-html="text"></div>
          </template>
          <template slot="imgSlot" slot-scope="text,record">
            <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
            <img v-else :src="getImgView(text)" :preview="record.id" height="25px" alt=""
              style="max-width:80px;font-size: 12px;font-style: italic;" />
          </template>
          <template slot="fileSlot" slot-scope="text">
            <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
            <a-button v-else :ghost="true" type="primary" icon="download" size="small" @click="downloadFile(text)">
              下载
            </a-button>
          </template>

          <span slot="action" slot-scope="text, record">
            <a @click="handleEdit(record)">编辑</a>

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

      <tab-ai-warning-modal ref="modalForm" @ok="modalFormOk"></tab-ai-warning-modal>
      <!-- <a-modal :visible.sync="previewVisible" footer="null" width="80%">
        <img :src="previewSrc" style="width:100%;height:auto;" />
      </a-modal> -->
    </div>
  </a-card>
</template>

<script>
  import '@/assets/less/TableExpand.less'
  import {
    mixinDevice
  } from '@/utils/mixin'
  import {
    JeecgListMixin
  } from '@/mixins/JeecgListMixin'
  import TabAiWarningModal from './modules/TabAiWarningModal'
  import {
    filterMultiDictText
  } from '@/components/dict/JDictSelectUtil'
  import {
    httpAction,
    getAction,
    getFileAccessHttpUrl
  } from '@/api/manage'
  export default {
    name: 'TabAiWarningList',
    mixins: [JeecgListMixin, mixinDevice],
    components: {
      TabAiWarningModal
    },
    data() {
      return {
        description: '报警信息管理页面',
        previewVisible: false,
        previewSrc: '',
        // 表头
        columns: [{
            title: '#',
            dataIndex: '',
            key: 'rowIndex',
            width: 60,
            align: "center",
            customRender: function(t, r, index) {
              return parseInt(index) + 1;
            }
          }, {
            title: '预警摄像头',
            align: "center",
            dataIndex: 'warningName'
          },
          
          {
            title: '预警类型',
            align: "center",
            dataIndex: 'warningType'
          },
          {
            title: '预警内容',
            align: "center",
            dataIndex: 'warningInfo'
          },
          {
            title: '预警图片',
            align: "center",
            dataIndex: 'warningPic',
            scopedSlots: {
              customRender: 'warningPic'
            }
          },
          {
            title: '预警视频',
            align: "center",
            dataIndex: 'warningCome',
            scopedSlots: {
              customRender: 'warningCome'
            }
          },
          {
            title: '预警时间',
            align: "center",
            dataIndex: 'warningTime',
            // customRender: function(text) {
            //   return !text ? "" : (text.length > 10 ? text.substr(0, 10) : text)
            // }
          },
          {
            title: '预警状态',
            align: "center",
            dataIndex: 'waringState'
          },
          {
            title: '预警算法',
            align: "center",
            dataIndex: 'waringAi'
          },
          {
            title: '预警消息',
            align: "center",
            dataIndex: 'waringText'
          },
          {
            title: '备注',
            align: "center",
            dataIndex: 'remake'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align: "center",
            fixed: "right",
            width: 200,
            scopedSlots: {
              customRender: 'action'
            }
          }
        ],
        url: {
          list: "/video/tabAiWarning/list",
          delete: "/video/tabAiWarning/delete",
          deleteBatch: "/video/tabAiWarning/deleteBatch",
          exportXlsUrl: "/video/tabAiWarning/exportXls",
          importExcelUrl: "video/tabAiWarning/importExcel",

        },
        dictOptions: {},
        superFieldList: [],
      }
    },
    created() {
      this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function() {
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },

    },
    methods: {
      openPreview(src) {
        this.previewSrc = src;
        this.previewVisible = true;
      },
      imgUrl() {
        return `${window._CONFIG['domianURL']}/sys/common/static`;
      },
      formatPath(path) {
        if (!path) return '';
        // 去掉盘符 (支持 D://, C://, E:// 等)
        return this.imgUrl() + path.replace(/^[A-Za-z]:[\\/]+opt[\\/]+upFiles/, '');
        // 例如 D://opt/upFiles/push/1758529414753.jpg → /opt/upFiles/push/1758529414753.jpg
      },
      initDictConfig() {},
      getSuperFieldList() {
        let fieldList = [];
        fieldList.push({
          type: 'int',
          value: 'warningType',
          text: '预警类型',
          dictCode: 'warning_type'
        })
        fieldList.push({
          type: 'string',
          value: 'warningInfo',
          text: '预警内容',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'warningCome',
          text: '预警视频地址',
          dictCode: ''
        })
        fieldList.push({
          type: 'date',
          value: 'warningTime',
          text: '预警时间'
        })
        fieldList.push({
          type: 'string',
          value: 'waringState',
          text: '预警状态',
          dictCode: 'waring_state'
        })
        fieldList.push({
          type: 'string',
          value: 'waringAi',
          text: '预警算法',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'waringText',
          text: '预警消息',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'remake',
          text: '备注',
          dictCode: ''
        })
        this.superFieldList = fieldList
      },
      batchTrain(){
        
      },
      oneTrain(){
        
      }
      }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';

  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
  .datagrid-view {
    height: 76vh !important;
  }

  /deep/.ant-table {
    height: calc(74vh - 91px) !important;
    overflow: auto;
  }
</style>