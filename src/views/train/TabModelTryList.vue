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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('模型预训练')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" class="dr" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
             <a-menu-item key="1" @click="batchStart()"><a-icon type="primary"/>批量恢复</a-menu-item>
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
		  <a @click="handleMake(record)">图片标注</a>
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

    <tab-model-try-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-model-try-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabModelTryModal from './modules/TabModelTryModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'
  import {
    httpAction,
    getAction,
    uploadAction 
  } from '@/api/manage'
  export default {
    name: 'TabModelTryList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabModelTryModal
    },
    data () {
      return {
        description: '模型预训练管理页面',
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
            title:'模型名称',
            align:"center",
            dataIndex: 'modelName'
          },
          {
            title:'模型类型',
            align:"center",
            dataIndex: 'modelType_dictText'
          },
          {
            title:'图片数量',
            align:"center",
            dataIndex: 'picNumber'
          },
          {
            title:'标签内容',
            align:"center",
            dataIndex: 'txtInfo'
          },
          {
            title:'标签文件',
            align:"center",
            dataIndex: 'txtTitle',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'图片文件(ZIP)',
            align:"center",
            dataIndex: 'picUrl',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'图片简称',
            align:"center",
            dataIndex: 'picName'
          },
          {
            title:'是否已有模型',
            align:"center",
            dataIndex: 'onnxIsok',
             customRender: (text) => (text==1?'有':'无'),
          },
          {
            title:'是否覆盖',
            align:"center",
            dataIndex: 'isInsert',
            customRender: (text) => (text ? filterMultiDictText(this.dictOptions['isInsert'], text) : ''),
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:260,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/train/tabModelTry/list",
          delete: "/train/tabModelTry/delete",
          deleteBatch: "/train/tabModelTry/deleteBatch",
          exportXlsUrl: "/train/tabModelTry/exportXls",
          importExcelUrl: "train/tabModelTry/importExcel",
          
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
      this.$set(this.dictOptions, 'isInsert', [{text:'是',value:'Y'},{text:'否',value:'N'}])
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
        fieldList.push({type:'string',value:'modelName',text:'模型名称',dictCode:''})
        fieldList.push({type:'string',value:'modelType',text:'模型类型',dictCode:''})
        fieldList.push({type:'string',value:'picNumber',text:'图片数量',dictCode:''})
        fieldList.push({type:'string',value:'txtTitle',text:'标签文件',dictCode:''})
        fieldList.push({type:'string',value:'picUrl',text:'图片地址',dictCode:''})
        fieldList.push({type:'string',value:'picName',text:'图片简称',dictCode:''})
        fieldList.push({type:'switch',value:'isInsert',text:'是否覆盖'})
        this.superFieldList = fieldList
      },
      handleMake(record){
                console.log(record);
               this.$router.push({path:'canvas/makeTitle',query:{id:record.id}});
      },
      startTrain(record){
        let that=this;
        getAction("/train/tabTrainPython/startPy", {id:record.id}).then((res) => {
          if (res.success) {
        
             that.$message.success("开始训练");
          
          } else {
           thast.$message.warning("训练失败");
          }
        })
      },
      batchStart(){
        var ids = ''
        for (var a = 0; a < this.selectedRowKeys.length; a++) {
          ids += this.selectedRowKeys[a] + ','
        }
        console.log("ids",ids)
        let that = this;
        this.$confirm({
          title: "确认批量操作吗？",
          content: "批量操请谨慎使用！",
          onOk: function() {
              
             // debugger;

            let url="/train/tabModelTry/getBatchPic?ids="+ids;

            httpAction(url,{}, "GET").then((res) => {
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
  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(74vh - 91px) !important;overflow: auto;
}
</style>