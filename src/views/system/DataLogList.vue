<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :md="6" :sm="8">
            <a-form-item label="表名">
              <a-input placeholder="请输入表名" v-model="queryParam.dataTable"></a-input>
            </a-form-item>
          </a-col>
          <a-col :md="6" :sm="8">
            <a-form-item label="数据ID">
              <a-input placeholder="请输入ID" v-model="queryParam.dataId"></a-input>
            </a-form-item>
          </a-col>

               <a-col :md="6" :sm="24">
          <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
                  <a-button type="primary" @click="searchQuery" class="cx">查询</a-button>
                  <a-button style="margin-left: 8px" @click="searchReset" class="cz">重置</a-button>
            </span>
               </a-col>
        </a-row>
      </a-form>
    </div>


 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleCompare()" type="primary" icon="plus">数据比较</a-button>
    </div>

    <!--table区 -->
    <div>
      <!--已选择的清空 -->
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i>已选择&nbsp;<a style="font-weight: 600">{{ selectedRowKeys.length }}</a>项&nbsp;&nbsp;
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>
      <a-table
        ref="table"
        size="middle"
        bordered
        rowKey="id"
        :columns="columns"
        :dataSource="dataSource"
        :pagination="ipagination"
        :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys,onChange: onSelectChange}"
        class="j-table-force-nowrap"
        @change="handleTableChange"
      >
        <!-- 字符串超长截取省略号显示-->
        <span slot="dataContent" slot-scope="text, record">
          <j-ellipsis :value="text" :length="80" />
        </span>
      </a-table>
    </div>
    <data-log-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></data-log-modal>
    </div>
  </a-card>
</template>

<script>
  import DataLogModal from './modules/DataLogModal'
  import {JeecgListMixin} from '@/mixins/JeecgListMixin'
  import JEllipsis from "@/components/jeecg/JEllipsis";

  export default {
    name: 'DataLogList',
    mixins: [JeecgListMixin],
    components: {
      JEllipsis,
      DataLogModal
    },
    data() {
      return {
        description: '数据日志管理页面',
        //表头
        columns: [
          {
            title: '表名',
            align: 'center',
            dataIndex: 'dataTable',
            width: "120"
          }, {
            title: '数据ID',
            align: 'center',
            dataIndex: 'dataId',
            width: "120"
          }, {
            title: '版本号',
            align: 'center',
            dataIndex: 'dataVersion',
            width: "50"
          }, {
            title: '数据内容',
            align: 'center',
            dataIndex: 'dataContent',
            width: "150",
            scopedSlots: {customRender: 'dataContent'},
          }, {
            title: '创建人',
            align: 'center',
            dataIndex: 'createBy',
            width: "100"
          },
        ],
        url: {
          list: "/sys/dataLog/list",
        },
      }
    },
    methods: {
      handleCompare: function () {
        if (!this.selectionRows || this.selectionRows.length != 2) {
          this.openNotifIcon('请选择两条数据');
          return false;
        } else if (this.selectionRows[0].dataId != this.selectionRows[1].dataId) {
          this.openNotifIcon('请选择相同的数据库表和数据ID进行比较');
          return false;
        } else {
          this.$refs.modalForm.addModal(this.selectionRows);
          this.$refs.modalForm.title = "数据比较";
        }
      },
      openNotifIcon(msg) {
        this.$notification['warning']({
          message: '提示信息',
          description: msg,
        });
      },
    }

  }

</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /deep/ .ant-table{height: calc(100vh - 431px);}
</style>