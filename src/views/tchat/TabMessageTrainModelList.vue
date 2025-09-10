<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="模型标题">
              <a-input placeholder="请输入模型标题" v-model="queryParam.modelTitle"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="语义分类数">
              <a-input placeholder="请输入语义分类数" v-model="queryParam.modelTypeNumber"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" @click="searchQuery" class="cx" icon="search">查询</a-button>
              <a-button type="primary" @click="searchReset" class="cz" icon="reload" style="margin-left: 8px">重置</a-button>
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('语音训练模型')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" class="dr" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
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

    <tab-message-train-model-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-message-train-model-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabMessageTrainModelModal from './modules/TabMessageTrainModelModal'

  export default {
    name: 'TabMessageTrainModelList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabMessageTrainModelModal
    },
    data () {
      return {
        description: '语音训练模型管理页面',
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
            title:'模型标题',
            align:"center",
            dataIndex: 'modelTitle'
          },
          {
            title:'语义分类数',
            align:"center",
            dataIndex: 'modelTypeNumber'
          },
          {
            title:'语义分类嵌入维度',
            align:"center",
            dataIndex: 'modelMessageInlay'
          },
          {
            title:'问答词嵌入维度',
            align:"center",
            dataIndex: 'modelBackInlay'
          },
          {
            title:'用户语句最大长度',
            align:"center",
            dataIndex: 'userMessageLenght'
          },
          {
            title:'最大回复长度',
            align:"center",
            dataIndex: 'modelMessageLenght'
          },
          {
            title:'模型训练增强',
            align:"center",
            dataIndex: 'modelEnhancement'
          },
          {
            title:'正则抑制系数',
            align:"center",
            dataIndex: 'inhibitionCoefficient'
          },
          {
            title:'生成语义可信阈值',
            align:"center",
            dataIndex: 'modelThreshold'
          },
          {
            title:'生成语义分类可信阈值',
            align:"center",
            dataIndex: 'modelTypeThreshold'
          },
          {
            title:'是否打印日志',
            align:"center",
            dataIndex: 'debugStatus'
          },
          {
            title:'日志详细信息',
            align:"center",
            dataIndex: 'debugId'
          },
          {
            title:'备注 ',
            align:"center",
            dataIndex: 'remark'
          },
          {
            title:'关键词敏感颗粒度',
            align:"center",
            dataIndex: 'modelSensitivity'
          },
          {
            title:'使用状态',
            align:"center",
            dataIndex: 'modelStatus'
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
          list: "/chat/tabMessageTrainModel/list",
          delete: "/chat/tabMessageTrainModel/delete",
          deleteBatch: "/chat/tabMessageTrainModel/deleteBatch",
          exportXlsUrl: "/chat/tabMessageTrainModel/exportXls",
          importExcelUrl: "chat/tabMessageTrainModel/importExcel",
          
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
        fieldList.push({type:'string',value:'modelTitle',text:'模型标题',dictCode:''})
        fieldList.push({type:'string',value:'modelTypeNumber',text:'语义分类数',dictCode:''})
        fieldList.push({type:'string',value:'modelMessageInlay',text:'语义分类嵌入维度',dictCode:''})
        fieldList.push({type:'string',value:'modelBackInlay',text:'问答词嵌入维度',dictCode:''})
        fieldList.push({type:'string',value:'userMessageLenght',text:'用户语句最大长度',dictCode:''})
        fieldList.push({type:'string',value:'modelMessageLenght',text:'最大回复长度',dictCode:''})
        fieldList.push({type:'string',value:'modelEnhancement',text:'模型训练增强',dictCode:''})
        fieldList.push({type:'string',value:'inhibitionCoefficient',text:'正则抑制系数',dictCode:''})
        fieldList.push({type:'double',value:'modelThreshold',text:'生成语义可信阈值',dictCode:''})
        fieldList.push({type:'string',value:'modelTypeThreshold',text:'生成语义分类可信阈值',dictCode:''})
        fieldList.push({type:'string',value:'debugStatus',text:'是否打印日志',dictCode:''})
        fieldList.push({type:'string',value:'debugId',text:'日志详细信息',dictCode:''})
        fieldList.push({type:'string',value:'remark',text:'备注 ',dictCode:''})
        fieldList.push({type:'string',value:'modelSensitivity',text:'关键词敏感颗粒度',dictCode:''})
        fieldList.push({type:'string',value:'modelStatus',text:'使用状态',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 374px);} */
.datagrid-view {
	height:66vh!important;
}
/deep/.ant-table {
	height:calc(64vh - 90px) !important;overflow: auto;
}
</style>