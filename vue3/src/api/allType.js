//
import request from '@/utils/request'

//获取子类
export function getChildrenType(params) {
    return request({
        url: '/stellaris/program/program/category/selectByParentProgramCategoryId',
        method: 'post',
        data: params
    })
}

export function getProgramPageType(params) {
    return request({
        url: '/stellaris/program/program/page',
        method: 'post',
        data: params
    })
}

//搜索功能
export function getProgramSearch(params) {
    return request({
        url: '/stellaris/program/program/search',
        method: 'post',
        data: params
    })
}
