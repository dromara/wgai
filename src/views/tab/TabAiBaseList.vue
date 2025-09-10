<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
 <div class="table-page-search-wrapper">
   <a-form layout="inline" @keyup.enter.native="searchQuery">
     <a-row :gutter="24">
       <a-col :xl="6" :lg="7" :md="8" :sm="24">
         <a-form-item label="英文标签名称">
           <a-input placeholder="英文标签名称" v-model="queryParam.englishName"></a-input>
         </a-form-item>
       </a-col>
       <a-col :xl="6" :lg="7" :md="8" :sm="24">
         <a-form-item label="中文标签名称">
           <a-input placeholder="请输入中文标签名称" v-model="queryParam.chainName"></a-input>
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('AI基础信息')">导出</a-button>
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
    <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
      
         <a-button @click="SendRedis" type="primary" icon="plus">刷新缓存</a-button> 
    </div>

    <!-- table区域-begin -->
    <div class="datagrid-view">
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a style="font-weight: 600">{{ selectedRowKeys.length }}项</a>
        <a class="btn" style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <!-- <a-table ref="table" size="middle" :scroll="{x:true}" bordered
        rowKey="id" :columns="columns" :dataSource="dataSource" :pagination="ipagination"
        :loading="loading" :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        class="j-table-force-nowrap" @change="handleTableChange"> -->
		
		<a-table :rowClassName="rowClassName" ref="table" size="middle" :scroll="{ x: true }" bordered
				:rowKey="record => record.key" :columns="columns" :dataSource="dataSource" :pagination="ipagination"
				:loading="loading" :rowSelection="{ selectedRowKeys: selectedRowKeys, onChange: onSelectChange }"
				@click.native="handleRowClick" class="j-table-force-nowrap" @change="handleTableChange">

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

    <tab-ai-base-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-ai-base-modal>
	</div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiBaseModal from './modules/TabAiBaseModal'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabAiBaseList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiBaseModal
    },
    data () {
      return {
        description: 'AI基础信息管理页面',
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
            title:'英文标签名称',
            align:"center",
            dataIndex: 'englishName'
          },
          {
            title:'中文标签名称',
            align:"center",
            dataIndex: 'chainName'
          },
          {
            title:'RGB颜色代码',
            align:"center",
            dataIndex: 'rgbColor'
          },
          {
            title:'CSS颜色代码',
            align:"center",
            dataIndex: 'cssColor'
          },
          {
            title:'示例图片',
            align:"center",
            dataIndex: 'sendUrl',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'报警内容',
            align:"center",
            dataIndex: 'remark'
          },
          {
            title:'语音播报内容',
            align:"center",
            dataIndex: 'spaceOne'
          },
          {
            title:'计数数量',
            align:"center",
            dataIndex: 'spaceTwo'
          },  {
            title:'是否推送第三方',
            align:"center",
            dataIndex: 'spaceThree'
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
          list: "/tab/tabAiBase/list",
          delete: "/tab/tabAiBase/delete",
          deleteBatch: "/tab/tabAiBase/deleteBatch",
          exportXlsUrl: "/tab/tabAiBase/exportXls",
          importExcelUrl: "tab/tabAiBase/importExcel",
          sendRedisUrl:"/tab/tabAiBase/sendRedis"
          
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
        fieldList.push({type:'string',value:'englishName',text:'英文标签名称',dictCode:''})
        fieldList.push({type:'string',value:'chainName',text:'中文标签名称',dictCode:''})
        fieldList.push({type:'string',value:'rgbColor',text:'RGB颜色代码',dictCode:''})
        fieldList.push({type:'string',value:'cssColor',text:'CSS颜色代码',dictCode:''})
        fieldList.push({type:'string',value:'sendUrl',text:'示例图片',dictCode:''})
        fieldList.push({type:'string',value:'remark',text:'备注',dictCode:''})
        this.superFieldList = fieldList
      },
      SendRedis(){
        let that = this;
        this.$confirm({
          title: "确认刷新缓存吗",
          content: "手动触发写入缓存!",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += that.url.sendRedisUrl;
            method = 'post';
            
            httpAction(httpurl, null, method).then((res) => {
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


