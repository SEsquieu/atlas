import {host,ble} from '/atlas/host.js';
const $=s=>document.querySelector(s);let device=null,char=null;
$('#scan').onclick=async()=>{try{device=(await ble.scan()).devices[0];$('#message').textContent='scanned'}catch(e){$('#message').textContent=e.code}};
$('#connect').onclick=async()=>{await ble.connect(device.device_id);$('#message').textContent='connected'};
$('#services').onclick=async()=>{char=(await ble.services()).services[0].characteristics[0];$('#message').textContent='services'};
$('#read').onclick=async()=>{$('#message').textContent='read '+(await ble.read(char.characteristic_id)).hex};
$('#write').onclick=async()=>{await ble.write(char.characteristic_id,$('#hex').value);$('#message').textContent='written'};
$('#subscribe').onclick=async()=>{await ble.watch(char.characteristic_id,event=>{$('#message').textContent='notification '+event.hex},error=>{$('#message').textContent=error.code})};
await host.ready();
