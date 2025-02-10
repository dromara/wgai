<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" @click="handleExportXls('训练结果')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
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
    <div>
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

    <tab-train-result-modal ref="modalForm" @ok="modalFormOk"></tab-train-result-modal>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabTrainResultModal from './modules/TabTrainResultModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'

  export default {
    name: 'TabTrainResultList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabTrainResultModal
    },
    data () {
      return {
        description: '训练结果管理页面',
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
            title:'开始时间',
            align:"center",
            dataIndex: 'startTime',
            customRender:function (text) {
              return !text?"":(text.length>10?text.substr(0,10):text)
            }
          },
          {
            title:'结束时间',
            align:"center",
            dataIndex: 'endTime',
            customRender:function (text) {
              return !text?"":(text.length>10?text.substr(0,10):text)
            }
          },
          {
            title:'训练状态',
            align:"center",
            dataIndex: 'trainState'
          },
          {
            title:'评估类别',
            align:"center",
            dataIndex: 'trainClass'
          },
          {
            title:'测试图片数量',
            align:"center",
            dataIndex: 'trainImages'
          },
          {
            title:'精度',
            align:"center",
            dataIndex: 'percision'
          },
          {
            title:'召回率',
            align:"center",
            dataIndex: 'recall'
          },
          {
            title:'平均精度',
            align:"center",
            dataIndex: 'map50'
          },
          {
            title:'0.5_0.9下的平均精度',
            align:"center",
            dataIndex: 'map5095'
          },
          {
            title:'标签图片',
            align:"center",
            dataIndex: 'labels',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'标签相关图',
            align:"center",
            dataIndex: 'labelsCorrelogram',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'训练批次0',
            align:"center",
            dataIndex: 'trainBatch0',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'训练批次1',
            align:"center",
            dataIndex: 'trainBatch1',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'训练批次2',
            align:"center",
            dataIndex: 'trainBatch2',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'PR曲线',
            align:"center",
            dataIndex: 'prCurve',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'混淆矩阵',
            align:"center",
            dataIndex: 'confusionMatrix',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'F1曲线',
            align:"center",
            dataIndex: 'f1Curve',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'P曲线',
            align:"center",
            dataIndex: 'ppCurve',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'R曲线',
            align:"center",
            dataIndex: 'rrCurve',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'结果曲线',
            align:"center",
            dataIndex: 'results',
            scopedSlots: {customRender: 'imgSlot'}
          },
          {
            title:'hyp文件',
            align:"center",
            dataIndex: 'hypYaml',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'opt文件',
            align:"center",
            dataIndex: 'optYaml',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'模型名称',
            align:"center",
            dataIndex: 'modelId_dictText'
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
          list: "/train/tabTrainResult/list",
          delete: "/train/tabTrainResult/delete",
          deleteBatch: "/train/tabTrainResult/deleteBatch",
          exportXlsUrl: "/train/tabTrainResult/exportXls",
          importExcelUrl: "train/tabTrainResult/importExcel",
          
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
        fieldList.push({type:'date',value:'startTime',text:'开始时间'})
        fieldList.push({type:'date',value:'endTime',text:'结束时间'})
        fieldList.push({type:'string',value:'trainState',text:'训练状态',dictCode:''})
        fieldList.push({type:'string',value:'trainClass',text:'评估类别',dictCode:''})
        fieldList.push({type:'string',value:'trainImages',text:'测试图片数量',dictCode:''})
        fieldList.push({type:'string',value:'percision',text:'精度',dictCode:''})
        fieldList.push({type:'string',value:'recall',text:'召回率',dictCode:''})
        fieldList.push({type:'string',value:'map50',text:'平均精度',dictCode:''})
        fieldList.push({type:'string',value:'map5095',text:'0.5_0.9下的平均精度',dictCode:''})
        fieldList.push({type:'string',value:'labels',text:'标签图片',dictCode:''})
        fieldList.push({type:'string',value:'labelsCorrelogram',text:'标签相关图',dictCode:''})
        fieldList.push({type:'string',value:'trainBatch0',text:'训练批次0',dictCode:''})
        fieldList.push({type:'string',value:'trainBatch1',text:'训练批次1',dictCode:''})
        fieldList.push({type:'string',value:'trainBatch2',text:'训练批次2',dictCode:''})
        fieldList.push({type:'string',value:'prCurve',text:'PR曲线',dictCode:''})
        fieldList.push({type:'string',value:'confusionMatrix',text:'混淆矩阵',dictCode:''})
        fieldList.push({type:'string',value:'f1Curve',text:'F1曲线',dictCode:''})
        fieldList.push({type:'string',value:'ppCurve',text:'P曲线',dictCode:''})
        fieldList.push({type:'string',value:'rrCurve',text:'R曲线',dictCode:''})
        fieldList.push({type:'string',value:'results',text:'结果曲线',dictCode:''})
        fieldList.push({type:'string',value:'hypYaml',text:'hyp文件',dictCode:''})
        fieldList.push({type:'string',value:'optYaml',text:'opt文件',dictCode:''})
        fieldList.push({type:'string',value:'modelId',text:'模型名称',dictCode:"tab_model_try,model_name,id"})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style scoped>
  @import '~@assets/less/common.less';
</style>