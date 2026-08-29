import request from '@/utils/request'

export function getProgramDetials(data) {
    return request({
        url: '/stellaris/program/program/detail',
        method: 'post',
        data:data

    })
}
