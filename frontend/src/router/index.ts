import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'workbench',
      component: () => import('@/views/WorkbenchView.vue'),
      meta: { title: '视频证据核验', crumb: '视频核验' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/TasksView.vue'),
      meta: { title: '核验任务', crumb: '核验任务' },
    },
    {
      path: '/tasks/new',
      name: 'new-task',
      component: () => import('@/views/NewTaskView.vue'),
      meta: { title: '新建核验任务', crumb: '新建核验任务' },
    },
    {
      path: '/materials',
      name: 'media',
      component: () => import('@/views/MediaView.vue'),
      meta: { title: '视频素材', crumb: '素材与字幕来源' },
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

export default router
