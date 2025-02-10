<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="任务名称">
              <a-input placeholder="请输入任务名称" v-model="queryParam.confName"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="可信阈值">
              <a-input placeholder="请输入可信阈值" v-model="queryParam.pth"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" @click="searchQuery" icon="search">查询</a-button>
              <a-button type="primary" @click="searchReset" icon="reload" style="margin-left: 8px">重置</a-button>
              <a @click="handleToggleSearch" style="margin-left: 8px">
                {{ toggleSearchStatus ? '收起' : '展开' }}
                <a-icon :type="toggleSearchStatus ? 'up' : 'down'" />
              </a>
            </span>
          </a-col>
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" @click="handleExportXls('训练任务')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl"
        @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery">
      </j-super-query>
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel">
            <a-icon type="delete" />删除
          </a-menu-item>
        </a-menu>
        <a-button style="margin-left: 8px"> 批量操作
          <a-icon type="down" />
        </a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div>
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a
          style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <a-table ref="table" size="middle" :scroll="{x:true}" bordered rowKey="id" :columns="columns"
        :dataSource="dataSource" :pagination="ipagination" :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}" class="j-table-force-nowrap"
        @change="handleTableChange">

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

          <a @click="bundPic(record)">绑定训练图片</a>
          <a-divider type="vertical" />
          <a @click="handleEdit(record)">开始训练</a>
          <a-divider type="vertical" />
          <a @click="handleEdit(record)">编辑</a>
          <a-divider type="vertical" />
          <a-dropdown>
            <a class="ant-dropdown-link">更多
              <a-icon type="down" />
            </a>
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

    <tab-easy-config-modal ref="modalForm" @ok="modalFormOk"></tab-easy-config-modal>
    <Select-Pic-Modal ref="selectPicModal" @selectFinished="selectOK"></Select-Pic-Modal>
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
  import TabEasyConfigModal from './modules/TabEasyConfigModal'
  
  import SelectPicModal from './SelectPicModal'
  export default {
    name: 'TabEasyConfigList',
    mixins: [JeecgListMixin, mixinDevice],
    components: {
      TabEasyConfigModal,SelectPicModal
    },
    data() {
      return {
        description: '训练任务管理页面',
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
          },
          {
            title: '创建日期',
            align: "center",
            dataIndex: 'createTime'
          },
          {
            title: '任务名称',
            align: "center",
            dataIndex: 'confName'
          },
          {
            title: '训练种类',
            align: "center",
            dataIndex: 'typeNum'
          },
          {
            title: '训练物体大小px',
            align: "center",
            dataIndex: 'boxSize'
          },
          {
            title: '图片数量',
            align: "center",
            dataIndex: 'picNum'
          },
          {
            title: '可信阈值',
            align: "center",
            dataIndex: 'pth'
          },
          {
            title: '背景图片',
            align: "center",
            dataIndex: 'backUrl',
            scopedSlots: {
              customRender: 'imgSlot'
            }
          },
          {
            title: '是否训练日志',
            align: "center",
            dataIndex: 'logStatic'
          },
          {
            title: '任务状态',
            align: "center",
            dataIndex: 'status_dictText'
          },
          {
            title: '绑定训练图片',
            align: "center",
            dataIndex: 'picList'
          },
          {
            title: '识别类别',
            align: "center",
            dataIndex: 'picTypeName'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align: "center",
            fixed: "right",
            width: 147,
            scopedSlots: {
              customRender: 'action'
            }
          }
        ],
        url: {
          list: "/easy/tabEasyConfig/list",
          delete: "/easy/tabEasyConfig/delete",
          deleteBatch: "/easy/tabEasyConfig/deleteBatch",
          exportXlsUrl: "/easy/tabEasyConfig/exportXls",
          importExcelUrl: "easy/tabEasyConfig/importExcel",

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
      initDictConfig() {},
      getSuperFieldList() {
        let fieldList = [];
        fieldList.push({
          type: 'datetime',
          value: 'createTime',
          text: '创建日期'
        })
        fieldList.push({
          type: 'string',
          value: 'confName',
          text: '任务名称',
          dictCode: ''
        })
        fieldList.push({
          type: 'int',
          value: 'typeNum',
          text: '训练种类',
          dictCode: ''
        })
        fieldList.push({
          type: 'int',
          value: 'boxSize',
          text: '训练物体大小px',
          dictCode: ''
        })
        fieldList.push({
          type: 'int',
          value: 'picNum',
          text: '图片数量',
          dictCode: ''
        })
        fieldList.push({
          type: 'double',
          value: 'pth',
          text: '可信阈值',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'backUrl',
          text: '背景图片',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'logStatic',
          text: '是否训练日志',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'status',
          text: '任务状态',
          dictCode: ''
        })
        fieldList.push({
          type: 'popup',
          value: 'picList',
          text: '绑定训练图片',
          popup: {
            code: '',
            field: '',
            orgFields: '',
            destFields: ''
          }
        })
        fieldList.push({
          type: 'list_multi',
          value: 'picTypeId',
          text: '类别',
          dictTable: "",
          dictText: '',
          dictCode: ''
        })
        this.superFieldList = fieldList
      },
      bundPic(info){
         this.$refs.selectPicModal.visible = true;
      },
      selectOK(data){
        console.log(data)
      }
    }
  }
</script>
<style scoped>
  @import '~@assets/less/common.less';
</style>
