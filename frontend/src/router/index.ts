import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: 'LiveProof 视频证据核验', crumb: '首页' },
    },
    {
      path: '/workbench',
      name: 'workbench',
      component: () => import('@/views/WorkbenchView.vue'),
      meta: { title: '视频证据核验', crumb: '视频核验' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/TasksView.vue'),
      meta: { title: '核验项目', crumb: '核验项目' },
    },
    {
      path: '/tasks/new',
      name: 'new-task',
      component: () => import('@/views/NewTaskView.vue'),
      meta: { title: '新建核验项目', crumb: '新建核验项目' },
    },
    {
      path: '/materials',
      name: 'media',
      component: () => import('@/views/MediaView.vue'),
      meta: { title: '素材库', crumb: '素材与字幕来源' },
    },
    {
      path: '/records',
      name: 'records',
      component: () => import('@/views/RecordsView.vue'),
      meta: { title: '核验记录', crumb: '核验记录' },
    },
    {
      path: '/todos',
      name: 'todos',
      component: () => import('@/views/TodosView.vue'),
      meta: { title: '待办复盘', crumb: '待办复盘' },
    },
  ],
})

/**
 * 首页曾长期是工作台本身，「地址栏里带 ?case=」就是工作台的深链接。
 *
 * <p>根路径改成首页后，任何已发出的 {@code /?case=xxx}（书签、聊天记录、报告里的跳转）
 * 若照常渲染首页，用户会看到「首页」而不是他本来要打开的那条事项，深链接等于失效。
 * 因此只要根路径带着工作台的定位参数，就整体搬到 {@code /workbench}，参数原样保留。
 *
 * <p>用 {@code replace}：这是地址纠正而不是用户选择，不该在浏览器历史里多留一层，
 * 否则用户点「后退」会回到被重定向的旧地址、再被重定向一次，卡在一个退不出去的循环里。
 *
 * <p>不带这两个参数时正常渲染首页：不能无条件把 {@code /} 改写成 {@code /workbench}，
 * 否则首页永远打不开。
 */
router.beforeEach((to) => {
  if (to.name !== 'home') return true
  if (!to.query.case && !to.query.material) return true
  return { name: 'workbench', query: to.query, replace: true }
})

export default router
