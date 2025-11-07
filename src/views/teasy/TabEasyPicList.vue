<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">

             <a-form-item label="所属模型" >
       <!--        <j-dict-select-tag v-model="model.modelId" dictCode="tab_model_try,model_name,id"  /> -->
                             <j-search-select-tag v-model="queryParam.modelId" dict="tab_model_try,model_name,id"  />
             </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
          
                <a-form-item label="是否标注" >
          <!--        <j-dict-select-tag v-model="model.modelId" dictCode="tab_model_try,model_name,id"  /> -->
                                <j-search-select-tag v-model="queryParam.markType" dict="is_open"  />
                </a-form-item>
             </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="图片名称">
              <a-input placeholder="请输入图片名称" v-model="queryParam.picName"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="图片地址">
              <a-input placeholder="请输入图片地址" v-model="queryParam.picUrl"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="标注标签">
              <a-input placeholder="请输入标注标签" v-model="queryParam.markTitle"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="标注特征">
              <a-input placeholder="请输入标注特征" v-model="queryParam.markFeature"></a-input>
            </a-form-item>
          </a-col>
          <template v-if="toggleSearchStatus">
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="备注">
                <a-input placeholder="请输入备注" v-model="queryParam.remake"></a-input>
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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('训练图片')">导出</a-button>
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
		    :scroll="{ x: 1400, y: 400 }"
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

    <tab-easy-pic-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-easy-pic-modal>
    </div>

  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabEasyPicModal from './modules/TabEasyPicModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'
import { filterObj } from '@/utils/util';
  export default {
    name: 'TabEasyPicList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabEasyPicModal
    },
    data () {
      return {
        description: '训练图片管理页面',
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
            title:'图片类型',
            align:"center",
            dataIndex: 'picType_dictText'
          },
          {
            title:'图片名称',
            align:"center",
            dataIndex: 'picName'
          },
          {
            title:'图片地址',
            align:"center",
            dataIndex: 'picUrl',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'remake'
          },
          {
            title:'是否标注',
            align:"center",
            dataIndex: 'markType'
          },
          {
            title:'标注文件',
            align:"center",
            dataIndex: 'markXml',
           scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'标注标签',
            align:"center",
            dataIndex: 'markTitle',
             ellipsis: true
          },  
          {
            title:'标注特征',
            align:"center",
            dataIndex: 'markFeature'
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
          list: "/easy/tabEasyPic/list",
          delete: "/easy/tabEasyPic/delete",
          deleteBatch: "/easy/tabEasyPic/deleteBatch",
          exportXlsUrl: "/easy/tabEasyPic/exportXls",
          importExcelUrl: "easy/tabEasyPic/importExcel",
          
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
      getQueryParams() {
        //获取查询条件
      
        var param = Object.assign(this.queryParam,this.filters);
        param.field = this.getQueryField();
        param.pageNo = this.ipagination.current;
        param.pageSize = this.ipagination.pageSize;
   
        return filterObj(param);
      },
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'sel_search',value:'picType',text:'图片类型',dictTable:"", dictText:'', dictCode:'pic_type'})
        fieldList.push({type:'string',value:'picName',text:'图片名称',dictCode:''})
        fieldList.push({type:'string',value:'picUrl',text:'图片地址',dictCode:''})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        fieldList.push({type:'string',value:'markType',text:'是否标注',dictCode:''})
        fieldList.push({type:'string',value:'markXml',text:'标注文件',dictCode:''})
        fieldList.push({type:'string',value:'markTitle',text:'标注标签',dictCode:''})
        fieldList.push({type:'string',value:'markFeature',text:'标注特征',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 505px);} */
.datagrid-view {
	height:calc(58vh + 2px)!important;
}
/deep/.ant-table {
	height:calc(50vh - 27px) !important;overflow: auto;
}
</style>