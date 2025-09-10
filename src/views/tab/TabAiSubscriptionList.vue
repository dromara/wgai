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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('Ai事件订阅')">导出</a-button>
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

		  <a-divider type="vertical" v-if="record.runState==0" />
		  <a  v-if="record.runState==0"  @click="handleRun(record,1)">开始执行</a>
		  <a-divider type="vertical"  v-if="record.runState==1"/>
		  <a  v-if="record.runState==1"  @click="handleRun(record,0)">结束执行</a>
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

    <tab-ai-subscription-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-ai-subscription-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiSubscriptionModal from './modules/TabAiSubscriptionModal'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabAiSubscriptionList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiSubscriptionModal
    },
    data () {
      return {
        description: 'Ai事件订阅管理页面',
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
            title:'解码脚本',
            align:"center",
            dataIndex: 'pyType_dictText'
          },
          {
            title:'是否需要前置',
            align:"center",
            dataIndex: 'isBegin_dictText'
          },
          {
            title:'前置模型',
            align:"center",
            dataIndex: 'beginEventTypes_dictText'
          },
          {
            title:'前置识别内容',
            align:"center",
            dataIndex: 'beginName'
          },
          {
            title:'订阅名称',
            align:"center",
            dataIndex: 'name'
          },
          {
            title:'订阅类型',
            align:"center",
            dataIndex: 'eventTypesName'
          },
          {
            title:'订阅回调地址',
            align:"center",
            dataIndex: 'eventUrl'
          },
          {
            title:'同类型报警间隔',
            align:"center",
            dataIndex: 'eventNumber'
          },
          {
            title:'运行状态',
            align:"center",
            dataIndex: 'runState_dictText'
          },
          // {
          //   title:'报警消息',
          //   align:"center",
          //   dataIndex: 'eventInfo'
          // },
          {
            title:'订阅地址URL',
            align:"center",
            dataIndex: 'remake'
          },
          {
            title:'推送状态',
            align:"center",
            dataIndex: 'pushStatic_dictText'
          }, {
            title:'设备编号',
            align:"center",
            dataIndex: 'indexCode'
          },
        
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:300,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/tab/tabAiSubscription/list",
          delete: "/tab/tabAiSubscription/delete",
          deleteBatch: "/tab/tabAiSubscription/deleteBatch",
          exportXlsUrl: "/tab/tabAiSubscription/exportXls",
          importExcelUrl: "tab/tabAiSubscription/importExcel",
          updateUrl:"tab/tabAiSubscription/edit"
          
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
        fieldList.push({type:'list_multi',value:'eventTypes',text:'订阅类型',dictTable:"", dictText:'', dictCode:''})
        fieldList.push({type:'string',value:'eventUrl',text:'订阅回调地址',dictCode:''})
        fieldList.push({type:'string',value:'eventNumber',text:'同类型报警间隔',dictCode:''})
        fieldList.push({type:'string',value:'eventInfo',text:'报警消息',dictCode:''})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        this.superFieldList = fieldList
      },
      handleRun(record,flag){
        let that = this;
        this.$confirm({
          title: "确认识别吗",
          content: "手动触发后一直执行,直到手动结束！",
          onOk: function() {

             // debugger;
            let url=that.url.updateUrl;
            record.runState=flag;
            httpAction(url, record, "POST").then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
                  that.loadData();
            }).finally(() => {
              that.confirmLoading = false;
            })
        
          }
        });
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 280px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(74vh - 91px) !important;overflow: auto;
}
</style>