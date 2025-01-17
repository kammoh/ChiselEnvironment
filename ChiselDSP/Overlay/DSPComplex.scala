/** New Complex 
  * TODO: +,- (unary-),* Complex [ how many muls ], * Real, * Imag, Conj, abs 
  * (all variations, with delays, truncation/round, overflow)
  * Case class parameterization?
  */

package ChiselDSP
import Chisel._

object Complex {

  /** Create a new Complex number: real + imag*i
    * @tparam T the type to represent the complex number with, eg DSPFixed, DSPDbl
    */
  def apply[T <: DSPQnm[T]](real: T, imag: T) : Complex[T] = new Complex(real, imag)
  def apply[T <: DSPQnm[T]](gen: T) : Complex[T] = new Complex(gen.cloneType, gen.cloneType)
  
  /** Creates non-Chisel complex class if real, imag inputs are type Scala Double */
  def apply(real:Double, imag:Double) : ScalaComplex = new ScalaComplex(real, imag)

  private[ChiselDSP] var opts: ComplexParams = ComplexParams()
  /** Returns complex params */
  def getFixedParams(): Tuple2[Int,Int] = (opts.intBits,opts.fracBits)
  def getFrac(): Int = opts.fracBits
  def getInt(): Int = opts.intBits
  def getAddPipe(): Double = opts.addPipe

}

/** Complex components */
abstract class ComplexType
case object Real extends ComplexType
case object Imag extends ComplexType

/** Default Complex hardware customizations */
case class ComplexParams (
  intBits: Int = 0,                   // Fixed in Qn.m notation --> n (# of integer bits, not including sign)
  fracBits: Int = 15,                 // Fixed in Qn.m notation --> m (# fractional bits, determines LSB)
  use4Muls:Boolean = true,            // Whether to use 4 real muls or 3 real muls for a complex mul
  mulPipe:Int = 0,                    // Amount of registers for retiming a fixed multiply (not complex multiply)
  addPipe:Double = 0.0,               // Amount of registers for retiming a fixed add (not complex add)
  trimType: TrimType = NoTrim,        // How to trim un-needed fractional bits (reduce precision)
  overflowType: OverflowType = Grow,  // How to handle overflow
  mulFracGrowth: Int = 1              // Product should have mulFracGrowth more fractional bits than inputs (when trim
                                      // enabled)
)

/** Complex class for normal Scala */
class ScalaComplex (var real:Double, var imag:Double){
  override def toString() = {real + (if (imag < 0) " - " + (-imag) else " + " + imag) + " i"}
  def toList() = List(real,imag)
}

/** Complex number representation */
private[ChiselDSP] abstract class ComplexBundle extends Bundle {
  def Q : String
}
class Complex[T <: DSPQnm[T]](val real: T, val imag: T) extends ComplexBundle {

  /** Returns a string containing the integer and fractional widths of the real + imaginary components*/
  def Q(): String = "[" + real.Q + "," + imag.Q + "]"

  /** Check that 'name' is a valid component of Complex, ie. real or imag. Any methods with
    * 0 arguments should be added to this list to prevent Chisel from stack overflowing... :(
    */
  override protected def checkPort(obj : Any, name : String) : Boolean = name match {
    case "real" => true
    case "imag" => true
    case _      => false
  }

  /** Clone a complex instantiation */
  override def cloneType() = {
    new Complex(real.cloneType, imag.cloneType).asInstanceOf[this.type]
  }

  /** Pipe (n, [optional] en)
  * Delay complex by n cycles (optional enable en)
  */
  def pipe (n: Int, en: DSPBool = DSPBool(true)): Complex[T] = Complex(real.pipe(n,en),imag.pipe(n,en))

  /** Register that keeps track of additional info */
  def reg(clock: Clock = null): Complex[T] = Complex(real.reg(clock),imag.reg(clock))
  
  /** Select function: s = true -> this; else 0 */
  def ? (s: DSPBool) : Complex[T] = Complex(real ? s, imag ? s)
  
  /** Custom bitwise or for muxing */
  def /| (b: Complex[T]) : Complex[T] = Complex(real /| b.real, imag /| b.imag)

  /** ARITHMETIC Right shift n */
  def >> (n: Int) : Complex[T] = Complex( real >> n, imag >> n)
  /** Variable ARITHMETIC Right shift n */
  def >> (n: DSPUInt) : Complex[T] = Complex( real >> n, imag >> n)
  
   /** ARITHMETIC Left shift n */
  def << (n: Int) : Complex[T] = Complex( real << n, imag << n)
  /** Variable ARITHMETIC Left shift n */
  def << (n: DSPUInt) : Complex[T] = Complex( real << n, imag << n)
  
  /** Equality check */
  def === (b: Complex[T]): DSPBool = { (real === b.real) & (imag === b.imag) }

  /** Inequality check */
  def =/= (b: Complex[T]): DSPBool = { (real =/= b.real) | (imag =/= b.imag) }

  def != (b: Complex[T]): DSPBool = (this =/= b)

  /** Less than (not abs value, but tests to see if real +
    * imaginary components separately + both satisfy condition
    */
  def < (b: Complex[T]): DSPBool = { (real < b.real) & (imag < b.imag) }

  /** Less than or equal (not abs value, but tests to see if real +
    * imaginary components separately + both satisfy condition
    */
  def <= (b: Complex[T]): DSPBool = { (real <= b.real) & (imag <= b.imag) }

  /** Greater than (not abs value, but tests to see if real +
    * imaginary components separately + both satisfy condition
    */
  def > (b: Complex[T]): DSPBool = { (real > b.real) & (imag > b.imag) }

  /** Greater than or equal (not abs value, but tests to see if real +
    * imaginary components separately + both satisfy condition
    */
  def >= (b: Complex[T]): DSPBool = { (real >= b.real) & (imag >= b.imag) }

  // TODO: Overflow handling for all operations below tis point

  /** Trim real, imag components to n fractional bits with desired trim type */
  def trim(n: Int, tType: TrimType = Complex.opts.trimType): Complex[T] = {
    if (tType == Truncate) Complex(real $ n, imag $ n)
    else if (tType == Round) Complex( real $$ n, imag $$ n)
    else this
  }

  /** Complex add */
  def + (b: Complex[T], aPipe: Int = math.floor(Complex.opts.addPipe).toInt,
         ofType: OverflowType = Complex.opts.overflowType): Complex[T] = {
    val sum = {
      if (ofType == Wrap) Complex( real +% b.real, imag +% b.imag)
      else if (ofType == Grow) Complex( real + b.real, imag + b.imag )
      else {Error("Overflow type not supported for complex + "); this}
      // Note: overflow needs to be handled after pipe
    }
    sum.pipe(aPipe)
  }
  // Needed for scala to recognize a + b (without optional params)
  def + (b: Complex[T]) : Complex[T] = this + (b,ofType = Complex.opts.overflowType)



  /** Complex sub */
  def - (b: Complex[T], aPipe: Int = math.floor(Complex.opts.addPipe).toInt,
         ofType: OverflowType = Complex.opts.overflowType): Complex[T] = {
    val diff = {
      if (ofType == Wrap) Complex( real -% b.real, imag -% b.imag)
      else if (ofType == Grow) Complex( real - b.real, imag - b.imag )
      else {Error("Overflow type not supported for complex - "); this}
    }
    diff.pipe(aPipe)
  }
  def - (b: Complex[T]) : Complex[T] = this - (b,ofType = Complex.opts.overflowType)

  /** Get the max frac width used in two complex values */
  private def getMaxFracWidth(b: Complex[T]) : Int = {
    (real,imag,b.real,b.imag) match {
      case (f0: DSPFixed, f1: DSPFixed, f2: DSPFixed, f3: DSPFixed) => {
         List(f0,f1,f2,f3).map(_.getFracWidth).max
      }
      case _ => 0                                                         // Don't care
    }
  }

  // TODO: Optimize unary sub
  /** Multiply by real, imaginary (selected @ compile time) */
  def ** (b: T, typ: ComplexType, mPipe: Int = Complex.opts.mulPipe,
         ofType: OverflowType = Complex.opts.overflowType, tType: TrimType = Complex.opts.trimType,
         fracGrowth: Int = Complex.opts.mulFracGrowth): Complex[T] = {
    val fracW = getMaxFracWidth(Complex(b,b))
    val prod = {
      if (typ == Real) Complex(real * b, imag * b)
      else Complex(imag * (-b),real * b)
    }
    prod.pipe(mPipe).trim(fracW + fracGrowth,tType)
  }

  /** Multiply by real (im = false) OR imaginary (im = true),selected @ runtime */
  def *? (b: T, im: DSPBool, mPipe: Int = Complex.opts.mulPipe,
         ofType: OverflowType = Complex.opts.overflowType, tType: TrimType = Complex.opts.trimType,
         fracGrowth: Int = Complex.opts.mulFracGrowth): Complex[T] = {
    val fracW = getMaxFracWidth(Complex(b,b))
    val newFracBits = fracW + fracGrowth
    val newb = Mux(im,-b,b)
    // Mul, Trim OF type
    val x = Trim((real * b).pipe(mPipe),newFracBits,tType)
    val y = Trim((imag * newb).pipe(mPipe),newFracBits,tType)
    val imDly = im.pipe(mPipe)
    val outr = Mux(imDly,y,x)
    val outi = Mux(imDly,x,y)
    Complex(outr,outi)
  }

  // TODO: 3 real muls for 1 complex mul, separate add ofType and mul ofType (?)
  /** Complex mul (note total register retiming delay = addPipe + mulPipe) */
  def * (b: Complex[T], aPipe: Int = math.floor(Complex.opts.addPipe).toInt, mPipe: Int = Complex.opts.mulPipe,
          ofType: OverflowType = Complex.opts.overflowType, tType: TrimType = Complex.opts.trimType,
          fracGrowth: Int = Complex.opts.mulFracGrowth, use4: Boolean = Complex.opts.use4Muls): Complex[T] = {
    val fracW = getMaxFracWidth(b)
    val newFracBits = fracW + fracGrowth
    val ac = Trim((real * b.real).pipe(mPipe),newFracBits,tType)
    val bd = Trim((imag * b.imag).pipe(mPipe),newFracBits,tType)
    val ad = Trim((real * b.imag).pipe(mPipe),newFracBits,tType)
    val bc = Trim((imag * b.real).pipe(mPipe),newFracBits,tType)
    val res = {
      if (ofType == Wrap) Complex(ac -% bd, ad +% bc)
      else if (ofType == Grow) Complex(ac - bd, ad + bc)
      else {Error("Overflow type not supported for complex * "); this}
    }
    res.pipe(aPipe)
  }
  def * (b: Complex[T]) : Complex[T] = this * (b,ofType = Complex.opts.overflowType)

}