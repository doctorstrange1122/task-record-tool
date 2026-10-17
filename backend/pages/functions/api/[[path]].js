/**
 * Pages Functions 入口：/api/* 全部交给已有的 Worker 逻辑处理
 * （注册/登录/数据同步/退出，逻辑与 src/worker.js 完全一致）
 */
import worker from '../../src/worker.js';

export async function onRequest(context) {
  return worker.fetch(context.request, context.env);
}
