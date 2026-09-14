import request from '@/utils/request'

export function orderCreateV5Api(data) {
    return request({
        url: '/stellaris/program/program/order/create/v5',
        method: 'post',
        data:data
    })
}

export function getOrderListApi(data) {
    return request({
        url: '/stellaris/order/order/select/list',
        method: 'post',
        data:data
    })
}

export function cancelOrderApi(data) {
    return request({
        url: '/stellaris/order/order/cancel',
        method: 'post',
        data:data
    })
}

export function getOrderDetailApi(data) {
    return request({
        url: '/stellaris/order/order/get',
        method: 'post',
        data:data
    })
}

export function getOrderMaterializationApi(data) {
    return request({
        url: '/stellaris/order/order/materialization',
        method: 'post',
        data
    })
}

/**
 * 订单支付
 * @param params
 * */
export function orderPayApi(params){
    return request({
        url: '/stellaris/order/order/pay',
        method: 'post',
        data: params
    })
}

/**
 * 检查订单支付状态
 * */
export function payCheckApi(params){
    return request({
        url: '/stellaris/order/order/pay/check',
        method: 'post',
        data: params
    })
}
