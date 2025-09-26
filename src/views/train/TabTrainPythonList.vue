<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="脚本名称">
              <a-input placeholder="请输入脚本名称" v-model="queryParam.pyName"></a-input>
            </a-form-item>
          </a-col>
         
          <template >
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="文件放置地址">
                <a-input placeholder="请输入文件放置地址" v-model="queryParam.pyPath"></a-input>
              </a-form-item>
            </a-col>
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="脚本备注">
                <a-input placeholder="请输入脚本备注" v-model="queryParam.pyRemake"></a-input>
              </a-form-item>
            </a-col>
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="脚本类型">
                <j-dict-select-tag placeholder="请选择脚本类型" v-model="queryParam.pyType" dictCode="py_type"/>
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('训练脚本模板')">导出</a-button>
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
           
          <a @click="startOne(record)">单步运行</a>
          <a-divider type="vertical" />
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

    <tab-train-python-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-train-python-modal>
    </div>
  </a-card>
</template>

<script>
import { filterObj } from '@/utils/util';
  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabTrainPythonModal from './modules/TabTrainPythonModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'
  import {
    httpAction,
    getAction,
    uploadAction 
  } from '@/api/manage'
  export default {
    name: 'TabTrainPythonList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabTrainPythonModal
    },
    data () {
      return {
        description: '训练脚本模板管理页面',
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
            title:'脚本名称',
            align:"center",
            dataIndex: 'pyName'
          },
          {
            title:'脚本文件',
            align:"center",
            dataIndex: 'pyUrl',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'文件放置地址',
            align:"center",
            dataIndex: 'pyPath'
          },
          {
            title:'训练额外命令',
            align:"center",
            dataIndex: 'spareOne'
          },
          {
            title:'导出额外命令',
            align:"center",
            dataIndex: 'spareTwo'
          },
          {
            title:'脚本备注',
            align:"center",
            dataIndex: 'pyRemake'
          },
          {
            title:'脚本类型',
            align:"center",
            dataIndex: 'pyType_dictText'
          },
          {
            title:'执行顺序',
            align:"center",
            sorter: true,
            dataIndex: 'pySort'
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
          list: "/train/tabTrainPython/list",
          delete: "/train/tabTrainPython/delete",
          deleteBatch: "/train/tabTrainPython/deleteBatch",
          exportXlsUrl: "/train/tabTrainPython/exportXls",
          importExcelUrl: "train/tabTrainPython/importExcel",
          
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
      // getQueryParams() {
      //   //获取查询条件
      
      //   var param = Object.assign({},this.filters);
      //   param.field = this.getQueryField();
      //   param.pageNo = this.ipagination.current;
      //   param.pageSize = this.ipagination.pageSize;
      //   param.column="pySort";
      //   param.order="asc";
      //   return filterObj(param);
      // },
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'pyName',text:'脚本名称',dictCode:''})
        fieldList.push({type:'string',value:'pyUrl',text:'脚本文件',dictCode:''})
        fieldList.push({type:'string',value:'pyPath',text:'文件放置地址',dictCode:''})
        fieldList.push({type:'string',value:'pyRemake',text:'脚本备注',dictCode:''})
        fieldList.push({type:'string',value:'pyType',text:'脚本类型',dictCode:'py_type'})
        fieldList.push({type:'int',value:'pySort',text:'执行顺序',dictCode:''})
        this.superFieldList = fieldList
      },
      startOne(record){
        let that=this;
        getAction("/train/tabTrainPython/startOnePy", {sort:record.pySort}).then((res) => {
          if (res.success) {
        
             that.$message.success("单步执行");
          
          } else {
             that.$message.warning("单步执行失败");
          }
        })
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