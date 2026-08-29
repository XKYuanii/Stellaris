import request from '@/utils/request'


/**
 * 修改个人信息
 * @param data
 * @returns {*}
 */
export function getPersonInfo(data){
    return request({
        url: '/stellaris/user/user/update',
        method: 'post',
        data:data
    })
}
export function getPersonInfoId(data){
    return request({
        url: '/stellaris/user/user/get/id',
        method: 'post',
        data:data
    })
}
