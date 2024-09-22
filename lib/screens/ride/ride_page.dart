import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../providers/reservation_provider.dart';
import '../../models/reservation.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../../services/reservation_service.dart';
import 'package:geolocator/geolocator.dart'; // 添加此行
import 'package:shared_preferences/shared_preferences.dart'; // 添加此行
import 'dart:convert'; // 添加此行

class RidePage extends StatefulWidget {
  const RidePage({super.key});

  @override
  RidePageState createState() => RidePageState();
}

class RidePageState extends State<RidePage> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  String? _qrCode;
  bool _isLoading = true;
  String _errorMessage = '';
  String _departureTime = '';
  String _routeName = '';
  String _codeType = '';

  bool _isGoingToYanyuan = true; // 给定初始值

  @override
  void initState() {
    super.initState();
    // 仅在初始时设定方向，不在刷新时改变方向
    _setDirectionBasedOnTime(DateTime.now());
    _initialize(); // 异步初始化
  }

  Future<void> _initialize() async {
    bool locationAvailable = await _determinePosition();
    if (locationAvailable) {
      await _setDirectionBasedOnLocation();
    }
    _loadRideData();
  }

  Future<bool> _determinePosition() async {
    bool serviceEnabled;
    LocationPermission permission;

    // 检查位置服务是否启用
    serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      // 位置服务未启用
      return false;
    }

    // 检查应用是否有权限访问位置
    permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        // 用户拒绝了位置权限
        return false;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      // 无法获取位置权限
      return false;
    }

    return true;
  }

  Future<void> _setDirectionBasedOnLocation() async {
    Position position = await Geolocator.getCurrentPosition();

    // 定义燕园和新校区的坐标
    const yanyuanLatitude = 39.989905;
    const yanyuanLongitude = 116.311271;
    const xinxiaoqLatitude = 40.177702;
    const xinxiaoqLongitude = 116.164600;

    bool isGoingToYanyuan;
    double distanceToYanyuan = Geolocator.distanceBetween(
      position.latitude,
      position.longitude,
      yanyuanLatitude,
      yanyuanLongitude,
    );

    double distanceToXinxiaoq = Geolocator.distanceBetween(
      position.latitude,
      position.longitude,
      xinxiaoqLatitude,
      xinxiaoqLongitude,
    );

    if (distanceToYanyuan < distanceToXinxiaoq) {
      // 用户在燕园，去新校区
      isGoingToYanyuan = false;
    } else {
      // 用户在新校区，去燕园
      isGoingToYanyuan = true;
    }

    // 在 setState 中更新变量
    setState(() {
      _isGoingToYanyuan = isGoingToYanyuan;
    });
  }

  void _setDirectionBasedOnTime(DateTime now) {
    _isGoingToYanyuan = now.hour < 12;
  }

  void _toggleDirection() {
    setState(() {
      _isGoingToYanyuan = !_isGoingToYanyuan;
      _errorMessage = ''; // 清空错误信息
    });
    _loadRideData();
  }

  Future<void> _loadRideData() async {
    setState(() {
      _isLoading = true; // 在这里设置为 true
      _errorMessage = ''; // 清空错误信息
    });
    final reservationProvider =
        Provider.of<ReservationProvider>(context, listen: false);
    final reservationService =
        ReservationService(Provider.of(context, listen: false));

    try {
      await reservationProvider.loadCurrentReservations();
      final validReservations = reservationProvider.currentReservations
          .where(_isWithinTimeRange)
          .where(
              (reservation) => _isInSelectedDirection(reservation.resourceName))
          .toList();

      if (validReservations.isNotEmpty) {
        if (validReservations.length == 1) {
          await _fetchQRCode(reservationProvider, validReservations[0]);
        } else {
          final selectedReservation = _selectReservation(validReservations);
          await _fetchQRCode(reservationProvider, selectedReservation);
        }
      } else {
        // 获取临时码
        final tempCode = await _fetchTempCode(reservationService);
        if (tempCode != null) {
          setState(() {
            _qrCode = tempCode['code'];
            _departureTime = tempCode['departureTime']!;
            _routeName = tempCode['routeName']!;
            _codeType = '临时码';
            _isLoading = false;
          });
        } else {
          setState(() {
            _errorMessage = '这会没有班车可坐😅';
            _isLoading = false;
          });
        }
      }
    } catch (e) {
      setState(() {
        _errorMessage = '加载数据时出错: $e';
        _isLoading = false;
      });
    }
  }

  Future<void> _fetchQRCode(
      ReservationProvider provider, Reservation reservation) async {
    try {
      await provider.fetchQRCode(
        reservation.id.toString(),
        reservation.hallAppointmentDataId.toString(),
      );
      setState(() {
        _qrCode = provider.qrCode;
        _departureTime = reservation.appointmentTime;
        _routeName = reservation.resourceName;
        _codeType = '乘车码';
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = '获取二维码时出错: $e';
        _isLoading = false;
      });
    }
  }

  Future<Map<String, String>?> _fetchTempCode(
      ReservationService service) async {
    // 新增代码：获取当前日期字符串
    final now = DateTime.now();
    final todayString = now.toIso8601String().split('T')[0];

    List<dynamic> buses;

    // 尝试从缓存中加载 busData
    final prefs = await SharedPreferences.getInstance();
    final cachedDate = prefs.getString('cachedDate');

    if (cachedDate == todayString) {
      // 如果缓存的日期是今天，使用缓存的 busData
      final cachedBusDataString = prefs.getString('cachedBusData');
      if (cachedBusDataString != null) {
        buses = jsonDecode(cachedBusDataString);
      } else {
        // 如果缓存为空，调用接口获取 busData
        buses = await service.getAllBuses([todayString]);
      }
    } else {
      // 如果缓存的日期不是今天，调用接口获取 busData
      buses = await service.getAllBuses([todayString]);
      // 更新缓存
      await prefs.setString('cachedBusData', jsonEncode(buses));
      await prefs.setString('cachedDate', todayString);
    }

    final validBuses = buses
        .where((bus) => _isWithinTimeRange(Reservation(
              id: 0,
              hallAppointmentDataId: 0,
              appointmentTime: '${bus['abscissa']} ${bus['yaxis']}',
              resourceName: bus['route_name'],
            )))
        .where((bus) => _isInSelectedDirection(bus['route_name']))
        .toList();

    print("validBuses: $validBuses");
    if (validBuses.isNotEmpty) {
      final bus = validBuses.first;
      final resourceId = bus['bus_id'].toString();
      final startTime = '${bus['abscissa']} ${bus['yaxis']}';
      print("resourceId: $resourceId");
      print("startTime: $startTime");
      final code = await service.getTempQRCode(resourceId, startTime);
      print("code: $code");
      return {
        'code': code,
        'departureTime': bus['yaxis'],
        'routeName': bus['route_name'],
      };
    }
    return null;
  }

  Reservation _selectReservation(List<Reservation> reservations) {
    final now = DateTime.now();
    final isGoingToYanyuan = now.hour < 12; // 假设中午12点前去燕园，之后回昌平
    return reservations.firstWhere(
      (r) => r.resourceName.contains(isGoingToYanyuan ? '燕园' : '昌平'),
      orElse: () => reservations.first,
    );
  }

  bool _isWithinTimeRange(Reservation reservation) {
    final now = DateTime.now();
    final appointmentTime = DateTime.parse(reservation.appointmentTime);
    final diffInMinutes = appointmentTime.difference(now).inMinutes;
    return appointmentTime.day == now.day &&
        diffInMinutes >= -10 &&
        diffInMinutes <= 30;
  }

  bool _isInSelectedDirection(String routeName) {
    final indexYan = routeName.indexOf('燕');
    final indexXin = routeName.indexOf('新');
    if (indexYan == -1 || indexXin == -1) return false;
    if (_isGoingToYanyuan) {
      return indexXin < indexYan;
    } else {
      return indexYan < indexXin;
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);

    return Scaffold(
      body: RefreshIndicator(
        onRefresh: _onRefresh,
        child: SingleChildScrollView(
          // 使用可滚动组件
          physics: AlwaysScrollableScrollPhysics(), // 确保可以下拉刷新
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height,
            ),
            child: _isLoading
                ? Center(child: CircularProgressIndicator())
                : _errorMessage.isNotEmpty
                    ? Center(child: Text(_errorMessage))
                    : _buildQRCodeDisplay(),
          ),
        ),
      ),
    );
  }

  Future<void> _onRefresh() async {
    await _loadRideData(); // 刷新数据，但不改变方向
  }

  Widget _buildQRCodeDisplay() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          QrImageView(
            data: _qrCode!,
            size: 200.0,
          ),
          SizedBox(height: 20),
          Text(
            _departureTime,
            style: TextStyle(fontSize: 16),
            textAlign: TextAlign.center,
          ),
          Text(
            _routeName,
            style: TextStyle(fontSize: 16),
            textAlign: TextAlign.center,
          ),
          Text(
            _codeType,
            style: TextStyle(fontSize: 16),
            textAlign: TextAlign.center,
          ),
          SizedBox(height: 20),
          // 添加"乘坐反向班车"小按钮
          ElevatedButton(
            onPressed: _toggleDirection,
            child: Text('乘坐反向班车'),
          ),
        ],
      ),
    );
  }
}
