<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="是否使用">
              <j-dict-select-tag placeholder="请选择是否使用" v-model="queryParam.isStart"  dictCode="run_state" />
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="热词">
              <a-input placeholder="请输入热词" v-model="queryParam.hotWord"></a-input>
            </a-form-item>
          </a-col>
          <template v-if="toggleSearchStatus">
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="encoder权重">
                <a-input placeholder="请输入encoder权重" v-model="queryParam.encoderPath"></a-input>
              </a-form-item>
            </a-col>
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="decoder权重">
                <a-input placeholder="请输入decoder权重" v-model="queryParam.decoderPath"></a-input>
              </a-form-item>
            </a-col>
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="joiner权重">
                <a-input placeholder="请输入joiner权重" v-model="queryParam.joinerPath"></a-input>
              </a-form-item>
            </a-col>
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="token占词">
                <a-input placeholder="请输入token占词" v-model="queryParam.tokenPath"></a-input>
              </a-form-item>
            </a-col>
          </template>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" class="cx" @click="searchQuery" icon="search">查询</a-button>
              <a-button type="primary" class="cz" @click="searchReset" icon="reload" style="margin-left: 8px">重置</a-button>
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('语音配置')">导出</a-button>
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

    <tab-audit-setting-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-audit-setting-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAuditSettingModal from './modules/TabAuditSettingModal'

  export default {
    name: 'TabAuditSettingList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAuditSettingModal
    },
    data () {
      return {
        description: '语音配置管理页面',
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
            title:'语音模型类型',
            align:"center",
            dataIndex: 'audioType_dictText'
          },
          {
            title:'是否使用',
            align:"center",
            dataIndex: 'isStart_dictText'
          },
          {
            title:'热词',
            align:"center",
            dataIndex: 'hotWord',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'encoder权重',
            align:"center",
            dataIndex: 'encoderPath',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'decoder权重',
            align:"center",
            dataIndex: 'decoderPath',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'joiner权重',
            align:"center",
            dataIndex: 'joinerPath',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'token占词',
            align:"center",
            dataIndex: 'tokenPath',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'识别词类型',
            align:"center",
            dataIndex: 'modeLing'
          },
          {
            title:'识别模式',
            align:"center",
            dataIndex: 'decodingMethod'
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'remake'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:200,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/audio/tabAuditSetting/list",
          delete: "/audio/tabAuditSetting/delete",
          deleteBatch: "/audio/tabAuditSetting/deleteBatch",
          exportXlsUrl: "/audio/tabAuditSetting/exportXls",
          importExcelUrl: "audio/tabAuditSetting/importExcel",
          
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
        fieldList.push({type:'int',value:'isStart',text:'是否使用',dictCode:"run_state"})
        fieldList.push({type:'string',value:'hotWord',text:'热词',dictCode:''})
        fieldList.push({type:'string',value:'encoderPath',text:'encoder权重',dictCode:''})
        fieldList.push({type:'string',value:'decoderPath',text:'decoder权重',dictCode:''})
        fieldList.push({type:'string',value:'joinerPath',text:'joiner权重',dictCode:''})
        fieldList.push({type:'string',value:'tokenPath',text:'token占词',dictCode:''})
        fieldList.push({type:'string',value:'modeLing',text:'识别词类型',dictCode:''})
        fieldList.push({type:'string',value:'decodingMethod',text:'识别模式',dictCode:''})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height:calc(100vh - 431px);} */
.datagrid-view {
	height:66vh!important;
}
/deep/.ant-table {
	height:calc(64vh - 90px) !important;overflow: auto;
}
</style>