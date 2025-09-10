<template>
  <a-card class="contablelist" :bordered="false">
 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="showModal(null)" type="primary" class="xz" icon="plus">新增</a-button>
    </div>
    <div class="datagrid-view">
      <a-table
        ref="table"
        size="middle"
        :scroll="{x:true}"
        bordered
        rowKey="id"
        :columns="columns"
        :dataSource="dataSource"
        :pagination="false"
        :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        class="j-table-force-nowrap"
        @change="handleTableChange">
         <span slot="status" slot-scope="text, record, index">
            <a-tag color="pink" v-if="text==0">禁用</a-tag>
            <a-tag color="#87d068" v-if="text==1" >正常</a-tag>
        </span>
        <span slot="action" slot-scope="text, record">
          <a @click="showModal(record)">编辑</a>

          <a-divider type="vertical"/>
          <a-dropdown>
            <a class="ant-dropdown-link">更多 <a-icon type="down"/></a>
            <a-menu slot="overlay">
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
    <gate-way-route-modal ref="modalForm" @ok="modalFormOk"></gate-way-route-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import GateWayRouteModal from './modules/GateWayRouteModal'

  export default {
    name: 'TenantList',
    mixins: [JeecgListMixin, mixinDevice],
    components: {
      GateWayRouteModal
    },
    data() {
      return {
        description: 'adad管理页面',
        // 表头
        columns: [
          {
            title: '路由ID',
            align: 'center',
            dataIndex: 'routerId'
          }, {
            title: '路由名称',
            align: 'center',
            dataIndex: 'name'
          },
          {
            title: '路由URI',
            align: 'center',
            dataIndex: 'uri'
          },
          {
            title: '状态',
            align: 'center',
            dataIndex: 'status',
            scopedSlots: { customRender: 'status' }
          },
          {
            title: '操作',
            dataIndex: 'action',
            align: 'center',
            fixed: 'right',
            width: 147,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: '/sys/gatewayRoute/list',
          delete: '/sys/gatewayRoute/delete'
        },
        dictOptions: {}
      }
    },
    created() {
    },
    methods: {
      showModal(record) {
        this.$refs['modalForm'].show(record)
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 227px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(84vh - 91px) !important;overflow: auto;
}
</style>