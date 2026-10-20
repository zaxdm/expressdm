import 'package:flutter/material.dart';

/// Paleta de Subo: amarillo, tinta y arena, con el petróleo para la ubicación
/// y las caminatas. La misma en la web y en la app.
/// Los nombres describen el color; quién usa cada uno se decide en
/// [buildTheme] y en las pantallas.
abstract final class Palette {
  static const ink = Color(0xFF15171C);

  /// El negro un punto más claro: detalles sobre una superficie negra.
  static const inkSoft = Color(0xFF2A2D34);

  /// Texto corrido que acompaña a un título.
  static const body = Color(0xFF3F434C);
  static const muted = Color(0xFF5F6470);

  /// Lo que casi no importa: contadores, fechas, pistas.
  static const faint = Color(0xFF858A95);
  static const accent = Color(0xFFFFC629);
  static const accentDark = Color(0xFFEBB10F);

  /// Casi negro, tirando a ámbar: el amarillo puro no se lee como texto sobre
  /// blanco, así que los rótulos y los iconos de acento van en este.
  static const accentText = Color(0xFF6B4C00);

  /// Amarillo suave: la pestaña activa, las insignias y los iconos.
  static const accentSoft = Color(0xFFFFF1C7);

  /// Amarillo muy suave: el fondo de un aviso o de una fila elegida.
  static const accentWash = Color(0xFFFFF8E3);
  static const line = Color(0xFFE8E5DD);

  /// El borde de un campo o de un botón blanco: un poco más marcado que [line].
  static const fieldLine = Color(0xFFDAD6CC);

  /// El relleno de una pastilla neutra o de un botón redondo.
  static const chip = Color(0xFFEFECE5);
  static const paper = Color(0xFFF5F3EE);
  static const surface = Colors.white;

  /// Petróleo: tu ubicación, las caminatas y los enlaces.
  static const petrol = Color(0xFF0F4C5C);
  static const petrolSoft = Color(0xFFDCEBEE);

  // Con significado propio: no siguen la marca.
  static const success = Color(0xFF15803D);
  static const successSoft = Color(0xFFDCF2E3);
  static const warning = Color(0xFF8A6100);
  static const warningSoft = Color(0xFFFFF1C7);
  static const danger = Color(0xFFB42318);
  static const dangerSoft = Color(0xFFFDE4E2);
}

ThemeData buildTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Palette.accent,
    brightness: Brightness.light,
  ).copyWith(
    primary: Palette.accent,
    onPrimary: Palette.ink,
    secondary: Palette.accentText,
    surface: Palette.surface,
    onSurface: Palette.ink,
    onSurfaceVariant: Palette.muted,
    outline: Palette.line,
    outlineVariant: Palette.line,
    error: Palette.danger,
  );

  final border = OutlineInputBorder(
    borderRadius: BorderRadius.circular(14),
    borderSide: const BorderSide(color: Palette.fieldLine),
  );

  return ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: Palette.paper,
    fontFamily: 'PlusJakartaSans',
    // Títulos gruesos y un poco apretados; el texto corrido, normal.
    textTheme: const TextTheme(
      headlineLarge: TextStyle(fontSize: 46, fontWeight: FontWeight.w800, letterSpacing: -1, height: 1.08),
      headlineMedium: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: -0.5, height: 1.15),
      titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: -0.2),
      titleMedium: TextStyle(fontSize: 17, fontWeight: FontWeight.w800, letterSpacing: 0),
      titleSmall: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, letterSpacing: 0),
      bodyLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w500, height: 1.5, letterSpacing: 0),
      bodyMedium: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, height: 1.5, letterSpacing: 0),
      bodySmall: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, height: 1.5, letterSpacing: 0, color: Palette.muted),
      labelLarge: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, letterSpacing: 0),
      labelMedium: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, letterSpacing: 0),
      labelSmall: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.6),
    ).apply(bodyColor: Palette.ink, displayColor: Palette.ink),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(borderSide: const BorderSide(color: Palette.ink, width: 1.6)),
      labelStyle: const TextStyle(color: Palette.muted),
      hintStyle: const TextStyle(color: Palette.faint),
    ),
    // Botones con esquinas de 16: el relleno, tinta (acción fuerte); el de
    // borde, blanco. El amarillo de la acción principal se pide en cada pantalla.
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Palette.ink,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 18),
        textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: Palette.ink,
        backgroundColor: Palette.surface,
        side: const BorderSide(color: Palette.ink),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: Palette.ink,
        textStyle: const TextStyle(fontWeight: FontWeight.w500),
        shape: const StadiumBorder(),
      ),
    ),
    cardTheme: CardThemeData(
      color: Palette.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: Palette.line),
      ),
    ),
    dividerTheme: const DividerThemeData(color: Palette.line, space: 1, thickness: 1),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating, width: 520),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected) ? Palette.ink : null,
      ),
    ),
  );
}
