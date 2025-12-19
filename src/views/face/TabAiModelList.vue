<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
 <div class="table-page-search-wrapper">
       <a-form layout="inline" @keyup.enter.native="searchQuery">
         <a-row :gutter="24">
           <a-col :xl="6" :lg="7" :md="8" :sm="24">
             <a-form-item label="模型名称">
               <a-input placeholder="模型名称" v-model="queryParam.aiName"></a-input>
             </a-form-item>
           </a-col>
           <a-col :xl="6" :lg="7" :md="8" :sm="24">
             <a-form-item label="模型类型">
                <j-dict-select-tag  v-model="queryParam.spareOne" placeholder="请选择模型类型"
                                              dictCode="model_type"/>
             </a-form-item>
           </a-col>
           <a-col :xl="6" :lg="7" :md="8" :sm="24">
             <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
               <a-button type="primary" @click="searchQuery" class="cx" icon="search">查询</a-button>
               <a-button type="primary" @click="searchReset" class="cz" icon="reload" style="margin-left: 8px">重置</a-button>
               <!-- <a @click="handleToggleSearch" style="margin-left: 8px">
                 {{ toggleSearchStatus ? '收起' : '展开' }}
                 <a-icon :type="toggleSearchStatus ? 'up' : 'down'"/>
               </a> -->
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('AI模型')">导出</a-button>
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
        :scroll="{x:auto}"
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
		 <!-- <a @click="handleNext(record)">模型下发</a> -->
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

    <tab-ai-model-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-ai-model-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiModelModal from './modules/TabAiModelModal'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabAiModelList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiModelModal
    },
    data () {
      return {
        description: 'AI模型管理页面',
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
            title:'定位模型名称',
            align:"center",
            dataIndex: 'aiName'
          },
          {
            title:'识别模型名称',
            align:"center",
            dataIndex: 'endName',
         
          },
          {
            title:'AI模型阈值',
            align:"center",
            dataIndex: 'threshold'
          },
          {
            title:'AI模型NMS阈值',
            align:"center",
            dataIndex: 'nmsThreshold'
          },
          {
            title:'AI模型类型',
            align:"center",
            dataIndex: 'spareOne_dictText'
          },
          {
            title:'AI模型识别方式',
            align:"center",
            dataIndex: 'modelDifyType_dictText'
          },
          {
            title:'AI模型识别类型',
            align:"center",
            dataIndex: 'modelDify_dictText'
          },
          {
            title:'AI解码方式',
            align:"center",
            dataIndex: 'modelJmType_dictText'
          },
         
          // {
          //   title:'AI配置文件',
          //   align:"center",
          //   dataIndex: 'aiConfig',
          //   scopedSlots: {customRender: 'fileSlot'}
          // },
          // {
          //   title:'AIName文件',
          //   align:"center",
          //   dataIndex: 'aiNameName',
          //   scopedSlots: {customRender: 'fileSlot'}
          // },
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
          list: "/tab/tabAiModel/listface",
          delete: "/tab/tabAiModel/delete",
          deleteBatch: "/tab/tabAiModel/deleteBatch",
          exportXlsUrl: "/tab/tabAiModel/exportXls",
          importExcelUrl: "tab/tabAiModel/importExcel",
          nextUrl:"/tab/tabAiModel/nextModel"
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
      handleNext(info){
        console.log("info", this.url);
        let that = this;
        this.$confirm({
          title: "确认下发模型吗",
          content: "模型会下发到模型列表中所有地址！",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += that.url.nextUrl;
            method = 'post';
        
            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
            }).finally(() => {
              that.confirmLoading = false;
            })
        
          }
        });
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'aiName',text:'AI模型名称',dictCode:''})
        fieldList.push({type:'string',value:'aiWeights',text:'AI权重文件',dictCode:''})
        fieldList.push({type:'string',value:'aiConfig',text:'AI配置文件',dictCode:''})
        fieldList.push({type:'string',value:'aiNameName',text:'AIName文件',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
.datagrid-view {
	height:66vh!important;
}
/deep/.ant-table {
	height:calc(64vh - 90px) !important;overflow: auto;
}
</style>